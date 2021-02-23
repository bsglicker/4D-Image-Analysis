package IJ_Plugins;

import ij.*;
import ij.plugin.PlugIn;
import ij.plugin.RoiEnlarger;
import ij.plugin.Duplicator;
import ij.process.*;
import ij.measure.*;
import ij.gui.*;

import java.awt.*;
import java.awt.event.*;

/* Enables the user to edit a 4-channel montage series to isolate individual cisternae. */
public class Edit_Montage_Series implements PlugIn, KeyListener, ImageListener, AdjustmentListener {
  
	private ImagePlus originalImage, editedImage;
	private ImageProcessor original, edited;
	private ImageWindow originalWindow, editedWindow;
	private ImageCanvas originalCanvas, editedCanvas;
	
	private int frames;                                                    // Number of time points.
	private int slices, columns, channels, GAP, width, height;
	private String title, editedTitle, info;
	private String[] parameters = new String[6];                           // Montage parameters.
	
	private static final int XCORNER = 10, YCORNER = 100, COLOR_ADJUST = 1000;
	private int[] adjust = new int[11];
	private int[] channelAdjust = {5, 5, 5};                            	  // Array index for adjust[].
	private ImageStatistics originalStats;
	private double wandAdjuster = 0.5;                                     // Adjusts borders for an automated Roi.
	private double fractionIncrement = 0.15;                               // Change for every 2x with an automated Roi.
	private ShapeRoi[] autoRoi;
	private ShapeRoi multiSliceRoi;
	
	//------------------------------------------------------------------------------------------------------------------------  
	
    public void run(String arg) {

      // Set default display values, and make an array for calculating adjusted display values.
      adjust[5] = COLOR_ADJUST;
      for (int i = 4; i >= 0; i--) {
        adjust[i] = adjust[i + 1] * 2;
      }
      for (int i = 6; i <= 10; i++) {
        adjust[i] = adjust[i - 1] / 2;
      }

      IJ.run("Open...");
      originalImage = IJ.getImage();
      originalWindow = originalImage.getWindow();
      originalImage.getCanvas().zoom100Percent();
      
      original = originalImage.getProcessor();
      originalWindow.setLocation(XCORNER, YCORNER);
      
      title = originalImage.getTitle();   
 
      if ( !(originalImage.getBitDepth() == 8)  || !(title.endsWith(".tif") || title.endsWith(".TIF")) || 
          !originalImage.isHyperStack() || !(originalImage.getNSlices() == 1) ) {
        IJ.showMessage("This plugin requires an 8-bit TIFF montage series.");
        originalImage.close();
        return;
      }
      
      frames = originalImage.getNFrames();
      
      // Get the montage parameters from the Info property, and convert from Strings to integers.
      info = (String) originalImage.getProperty("Info");
      parameters = info.split("\n");
      slices = extractParameter(parameters[0]);
      columns = extractParameter(parameters[1]);
      channels = extractParameter(parameters[2]);
      GAP = extractParameter(parameters[3]);
      width = extractParameter(parameters[4]);
      height = extractParameter(parameters[5]);
      
      Calibration cal = originalImage.getCalibration();
      originalImage.setCalibration(null);
      originalStats = originalImage.getStatistics();                        // Get uncalibrated statistics.
      originalImage.setCalibration(cal);
     
      GenericDialog gd = new GenericDialog("Choose Edited Montage");
      String[] secondFileOptions = {"Open Existing Montage", "Create New Montage"};
      gd.addChoice("Edited montage series:", secondFileOptions, secondFileOptions[0]);
      gd.showDialog();
      if (gd.wasCanceled()) return;
      
      String secondFileOption = gd.getNextChoice();
      
      if (secondFileOption.equals(secondFileOptions[0])) {
        // Open an existing montage series.
        IJ.run("Open...");
        editedImage = IJ.getImage();
        editedTitle = editedImage.getTitle();
        if ( !(editedImage.getBitDepth() == 8)  || !(editedTitle.endsWith(".tif") || editedTitle.endsWith(".TIF")) || 
            !editedImage.isHyperStack() || !(editedImage.getNSlices() == 1) ) {
          IJ.showMessage("The edited image must be an 8-bit TIFF montage series.");
          originalImage.close();
          editedImage.close();
          return;
        }
        if (!(editedImage.getProperty("Info").equals(originalImage.getProperty("Info")))) {
          IJ.showMessage("The properties of the edited image must match those of the original image.");
          originalImage.close();
          editedImage.close();
          return;
        }
      }
      else {
        // Create a new montage series.
        editedImage = new Duplicator().run(originalImage);
        editedImage.setTitle(title.substring(0, title.length() - 4) + " edited.tif");
        editedTitle = editedImage.getTitle();
        editedImage.show();
      }
      
      edited = editedImage.getProcessor();
      editedWindow = editedImage.getWindow();
      
      editedWindow.setLocation(XCORNER, YCORNER);
      
      originalCanvas = originalImage.getCanvas();
      editedCanvas = editedImage.getCanvas();
      
      // Add key listeners to the two windows.
      originalWindow.removeKeyListener(IJ.getInstance());
      editedWindow.removeKeyListener(IJ.getInstance());
      originalCanvas.removeKeyListener(IJ.getInstance());
      editedCanvas.removeKeyListener(IJ.getInstance());
      originalWindow.addKeyListener(this);
      editedWindow.addKeyListener(this);
      originalCanvas.addKeyListener(this);
      editedCanvas.addKeyListener(this);
      
      // Add adjustment listeners to the two scroll bars in each window.
      Component[] originalSliders = originalWindow.getComponents();
      Component[] editedSliders = editedWindow.getComponents();
      for (int i = 1; i <=2; i++) {
        ((ScrollbarWithLabel) originalSliders[i]).addAdjustmentListener(this);
        ((ScrollbarWithLabel) editedSliders[i]).addAdjustmentListener(this);
      }
      
      // Create a ShapeRoi array to hold the Roi's that will be generated by auto-tracing.
      autoRoi = new ShapeRoi[slices];
    }

    //========================================================================================================================
    
    /* Implements custom responses to specific key presses. */
    public void keyPressed(KeyEvent e) {
      int keyCode = e.getKeyCode();
      char keyChar = Character.toUpperCase(e.getKeyChar());
      boolean modifier = e.getModifiers() == 8;        					// Opt (or Alt) key
      
      ImageWindow activeWindow = WindowManager.getCurrentWindow();
      ImagePlus activeImage = activeWindow.getImagePlus();
      ImagePlus inactiveImage = activeImage.equals(editedImage)?originalImage:editedImage;
      int channel = activeImage.getChannel();
      int frame = activeImage.getFrame();
        
      boolean[] activeChannelsOriginal = ((CompositeImage) originalImage).getActiveChannels();
      boolean[] activeChannelsEdited = ((CompositeImage) editedImage).getActiveChannels();
      
      if (channels == 3) {                                              // turn off blue channel
        activeChannelsOriginal[2] = false;
        activeChannelsEdited[2] = false;
      }
      else if (channels == 2) {                                         // turn off red and blue channels
        activeChannelsOriginal[0] = activeChannelsOriginal[2] = false;
        activeChannelsEdited[0] = activeChannelsEdited[2] = false;
      }
        
      int[] histogram = originalStats.histogram;
      boolean found = false;
      int i, cutoff, max, sum;
      
      editedTitle = editedImage.getTitle();                             // in case the user changed it
      
      switch (keyCode) {
        
        case (8):                                                       // Delete => preserve only the Roi
          if (activeWindow.equals(originalWindow)) {
            IJ.showMessage("Please work with the edited image.");
            originalImage.killRoi();
            IJ.selectWindow(title);
            return;
          }
          Roi roi = editedImage.getRoi();
          if (roi == null) {
            IJ.run("Select All");
          }
          if (channel == 4) {                                                                               // don't delete in the gray channel
            if(!activeChannelsEdited[0] && !activeChannelsEdited[1] && activeChannelsEdited[2]) {           // set to blue if appropriate
              activeImage.setPosition(3, 1, frame);
              inactiveImage.setPosition(3, 1, frame);;
            }
            else if (!activeChannelsEdited[0] && activeChannelsEdited[1] && !activeChannelsEdited[2]) {     // set to green if appropriate
              activeImage.setPosition(2, 1, frame);
              inactiveImage.setPosition(2, 1, frame);
            }
            else if (activeChannelsEdited[0] && !activeChannelsEdited[1] && !activeChannelsEdited[2]) {     // set to red if appropriate
              activeImage.setPosition(1, 1, frame);
              inactiveImage.setPosition(1, 1, frame);
            }
            else {
              IJ.showMessage("Please work with a fluorescence channel.");
              IJ.selectWindow(editedTitle);
              return;
            }
          }
          else if (!activeChannelsEdited[channel - 1]) {
            if(!activeChannelsEdited[0] && !activeChannelsEdited[1] && activeChannelsEdited[2]) {           // set to blue if appropriate
              activeImage.setPosition(3, 1, frame);
              inactiveImage.setPosition(3, 1, frame);;
            }
            else if (!activeChannelsEdited[0] && activeChannelsEdited[1] && !activeChannelsEdited[2]) {     // set to green if appropriate
              activeImage.setPosition(2, 1, frame);
              inactiveImage.setPosition(2, 1, frame);
            }
            else if (activeChannelsEdited[0] && !activeChannelsEdited[1] && !activeChannelsEdited[2]) {     // set to red if appropriate
              activeImage.setPosition(1, 1, frame);
              inactiveImage.setPosition(1, 1, frame);
            }
            else {
              IJ.showMessage("Please work with a visible channel.");
              IJ.selectWindow(editedTitle);
              return;
            }
          }
          edited.setColor(Color.black);
          if (modifier)
            edited.fill(roi);                                             // Opt (or Alt) => delete the Roi
          else
            edited.fillOutside(roi);
          editedImage.killRoi();
          editedImage.updateAndDraw();
          editedImage.changes = true;
          break;
          
        //-------------------------------------------------------------------------------------------------------------------
        
        case (9):                                                       // Tab => swap windows
          if (activeWindow.equals(originalWindow)) {
            IJ.selectWindow(editedTitle);
          }
          else if (activeWindow.equals(editedWindow)) {
            IJ.selectWindow(title);
          }
          break;
          
        //-------------------------------------------------------------------------------------------------------------------
        
        case 37:                                                        // Left Arrow
          if (modifier) {                                               // previous channel
            if (channel > 1) {
              activeImage.setPosition(channel - 1, 1, frame);
              inactiveImage.setPosition(channel - 1, 1, frame);
            }
          }
          else {                                                        // previous frame
            if (frame > 1) {
              activeImage.setPosition(channel, 1, frame - 1);
              inactiveImage.setPosition(channel, 1, frame - 1);
            }
          }
          break;
          
        //-------------------------------------------------------------------------------------------------------------------
          
        case 39:                                                        // Right Arrow
          if (modifier) {                                               // next channel
            if (channel < 4) {
              activeImage.setPosition(channel + 1, 1, frame);
              inactiveImage.setPosition(channel + 1, 1, frame);
            }
          }
          else {                                                        // next frame
            if (frame < frames) {
              activeImage.setPosition(channel, 1, frame + 1);
              inactiveImage.setPosition(channel, 1, frame + 1);
            }
          }
          break;
          
        //-------------------------------------------------------------------------------------------------------------------
          
        case 38:                                                        // Up Arrow
          if (modifier) {                                               // jump to medium or top adjust array value
            if (channelAdjust[channel - 1] < 5)
              channelAdjust[channel - 1] = 5;
            else
              channelAdjust[channel - 1] = 10; 
          }
          else {                                                        // increase adjust index value
            if (channelAdjust[channel - 1] < 10)
              channelAdjust[channel - 1] ++;
          }
          cutoff = originalStats.pixelCount/adjust[channelAdjust[channel - 1]];
          i = 256;
          sum = 0;
          while (!found) {
            i--;
            sum += histogram[i];
            found = sum > cutoff;
          }
          max = i;
          activeImage.setDisplayRange(0, max);
          inactiveImage.setDisplayRange(0, max);
          activeImage.updateAndDraw();
          inactiveImage.updateAndDraw();
          activeImage.changes = true;
          inactiveImage.changes = true;
          break;
          
        //-------------------------------------------------------------------------------------------------------------------
          
        case 40:                                                        // Down Arrow
          if (modifier) {                                               // jump to medium or bottom adjust array value
            if (channelAdjust[channel - 1] > 5)
              channelAdjust[channel - 1] = 5;
            else
              channelAdjust[channel - 1] = 0; 
          }
          else {                                                        // decrease adjust index value
            if (channelAdjust[channel - 1] > 0)
              channelAdjust[channel - 1] --;
          }
          cutoff = originalStats.pixelCount/adjust[channelAdjust[channel - 1]];
          i = 256;
          sum = 0;
          while (!found) {
            i--;
            sum += histogram[i];
            found = sum > cutoff;
          }
          max = i;
          activeImage.setDisplayRange(0, max);
          inactiveImage.setDisplayRange(0, max);
          activeImage.updateAndDraw();
          inactiveImage.updateAndDraw();
          activeImage.changes = true;
          inactiveImage.changes = true;
          break;
          
        //-------------------------------------------------------------------------------------------------------------------
          
        case (10):                                                      // Return => auto-outline the spot in 3D
          if (activeWindow.equals(originalWindow)) {
            IJ.showMessage("Please work with the edited image.");
            originalImage.killRoi();
            IJ.selectWindow(title);
            return;
          }
          if (channel == 4) {                                                                               // don't work in the gray channel
            if(!activeChannelsEdited[0] && !activeChannelsEdited[1] && activeChannelsEdited[2]) {           // set to blue if appropriate
              activeImage.setPosition(3, 1, frame);
              inactiveImage.setPosition(3, 1, frame);;
            }
            else if (!activeChannelsEdited[0] && activeChannelsEdited[1] && !activeChannelsEdited[2]) {     // set to green if appropriate
              activeImage.setPosition(2, 1, frame);
              inactiveImage.setPosition(2, 1, frame);
            }
            else if (activeChannelsEdited[0] && !activeChannelsEdited[1] && !activeChannelsEdited[2]) {     // set to red if appropriate
              activeImage.setPosition(1, 1, frame);
              inactiveImage.setPosition(1, 1, frame);
            }
            else {
              IJ.showMessage("Please work with a fluorescence channel.");
              IJ.selectWindow(editedTitle);
              return;
            }
          }
          else if (!activeChannelsEdited[channel - 1]) {
            if(!activeChannelsEdited[0] && !activeChannelsEdited[1] && activeChannelsEdited[2]) {           // set to blue if appropriate
              activeImage.setPosition(3, 1, frame);
              inactiveImage.setPosition(3, 1, frame);;
            }
            else if (!activeChannelsEdited[0] && activeChannelsEdited[1] && !activeChannelsEdited[2]) {     // set to green if appropriate
              activeImage.setPosition(2, 1, frame);
              inactiveImage.setPosition(2, 1, frame);
            }
            else if (activeChannelsEdited[0] && !activeChannelsEdited[1] && !activeChannelsEdited[2]) {     // set to red if appropriate
              activeImage.setPosition(1, 1, frame);
              inactiveImage.setPosition(1, 1, frame);
            }
            else {
              IJ.showMessage("Please work with a visible channel.");
              IJ.selectWindow(editedTitle);
              return;
            }
          }
          
          Roi userRoi = editedImage.getRoi();                           // initial Roi specified by the user
        
          // Make sure that a single spot has been selected.
          if (userRoi == null) {
            IJ.beep();
            return;
          }
          else {
            if (userRoi.getBounds().width > width || userRoi.getBounds().height > height) {
              IJ.showMessage("Please draw the selection around a single spot.");
              IJ.selectWindow(editedTitle);
              return;
            }
          }
          
          userRoi = refineRoi(userRoi);
          
          if (userRoi == null) {
            IJ.showMessage("You're doing something weird.\n \nMake sure the correct channel is selected.");
            IJ.selectWindow(editedTitle);
            return;
          }
          
          // Reset everything.
          editedImage.killRoi();
          for (int j = 0; j < slices; j++) {
            autoRoi[j] = null;
          }
        
          int x = userRoi.getBounds().x;
          int y = userRoi.getBounds().y;
          
          int userRoiColumn = (x - GAP) / (width + GAP) + 1;
          int userRoiRow = (y - GAP) / (height + GAP) + 1;
          int userRoiSlice = (userRoiRow - 1) * columns + userRoiColumn - 1;    // slice index starts at 0

          multiSliceRoi = new ShapeRoi(userRoi);                                // corresponds to autoRoi[userRoiSlice]
          
          // Automatically trace Roi's in the slices before the current slice.
          Roi copyRoi = (PolygonRoi) userRoi.clone();                           // initialize copyRoi to match userRoi
          if (userRoiSlice > 0) {
            for (int roiSlice = userRoiSlice - 1; roiSlice >= 0; roiSlice--) {
              x = copyRoi.getBounds().x;
              y = copyRoi.getBounds().y;
              copyRoi.setLocation(x + xOffset(roiSlice + 1, roiSlice), y + yOffset(roiSlice + 1, roiSlice));
              copyRoi = refineRoi(copyRoi);
              if (copyRoi == null) {
                editedImage.killRoi();
                break;
              }
              autoRoi[roiSlice] = new ShapeRoi(copyRoi);
            }
          }
          
          // Automatically trace Roi's in the slices after the current slice.
          copyRoi = (PolygonRoi) userRoi.clone();                               // re-initialize copyRoi
          if (userRoiSlice < slices - 1) {
            for (int roiSlice = userRoiSlice + 1; roiSlice <= slices - 1; roiSlice++) {
              x = copyRoi.getBounds().x;
              y = copyRoi.getBounds().y;
              copyRoi.setLocation(x + xOffset(roiSlice - 1, roiSlice), y + yOffset(roiSlice - 1, roiSlice));
              copyRoi = refineRoi(copyRoi);
              if (copyRoi == null) {
                editedImage.killRoi();
                break;
              }
              autoRoi[roiSlice] = new ShapeRoi(copyRoi);
            }
          }
          
          for (int roiSlice = 0; roiSlice < slices; roiSlice++) {
            if (autoRoi[roiSlice] != null)
              multiSliceRoi.or(autoRoi[roiSlice]);
          }
          editedImage.setRoi(multiSliceRoi);
          break;
          
      }
      
      //---------------------------------------------------------------------------------------------------------------------
      
      switch (keyChar) {
        
        case ('R'):                                                         // toggle red channel
          activeChannelsOriginal[0] = !activeChannelsOriginal[0];
          activeChannelsEdited[0] = !activeChannelsEdited[0];
          if(!activeChannelsEdited[0] && !activeChannelsEdited[1] && activeChannelsEdited[2]) {           // set to blue if appropriate
            activeImage.setPosition(3, 1, frame);
            inactiveImage.setPosition(3, 1, frame);;
          }
          else if (!activeChannelsEdited[0] && activeChannelsEdited[1] && !activeChannelsEdited[2]) {     // set to green if appropriate
            activeImage.setPosition(2, 1, frame);
            inactiveImage.setPosition(2, 1, frame);
          }
          activeImage.updateAndDraw();
          inactiveImage.updateAndDraw();
          break;
          
        case ('G'):                                                         // toggle green channel
          activeChannelsOriginal[1] = !activeChannelsOriginal[1];
          activeChannelsEdited[1] = !activeChannelsEdited[1];
          if(!activeChannelsEdited[0] && !activeChannelsEdited[1] && activeChannelsEdited[2]) {           // set to blue if appropriate
            activeImage.setPosition(3, 1, frame);
            inactiveImage.setPosition(3, 1, frame);;
          }
          else if (activeChannelsEdited[0] && !activeChannelsEdited[1] && !activeChannelsEdited[2]) {     // set to red if appropriate
            activeImage.setPosition(1, 1, frame);
            inactiveImage.setPosition(1, 1, frame);
          }
          activeImage.updateAndDraw();
          inactiveImage.updateAndDraw();
          break;
          
        case ('B'):                                                         // toggle blue channel
          activeChannelsOriginal[2] = !activeChannelsOriginal[2];
          activeChannelsEdited[2] = !activeChannelsEdited[2];
          if(!activeChannelsEdited[0] && activeChannelsEdited[1] && !activeChannelsEdited[2]) {           // set to green if appropriate
            activeImage.setPosition(2, 1, frame);
            inactiveImage.setPosition(2, 1, frame);
          }
          else if (activeChannelsEdited[0] && !activeChannelsEdited[1] && !activeChannelsEdited[2]) {     // set to red if appropriate
            activeImage.setPosition(1, 1, frame);
            inactiveImage.setPosition(1, 1, frame);
          }
          activeImage.updateAndDraw();
          inactiveImage.updateAndDraw();
          break;
          
        case ('Y'):                                                         // toggle gray channel
          activeChannelsOriginal[3] = !activeChannelsOriginal[3];
          activeChannelsEdited[3] = !activeChannelsEdited[3];
          activeImage.updateAndDraw();
          inactiveImage.updateAndDraw();
          break;
          
        case ('S'):                                                         // save changes
          if (activeWindow.equals(originalWindow)) {
            IJ.beep();
          }
          else if (activeWindow.equals(editedWindow)) {
            IJ.run("Save");
          }
          
          break;
          
        case ('A'):
          IJ.run("Select All");
          break;
          
        case ('Z'):                                                         // copy original to edited
          edited.insert(original, 0, 0);
          editedImage.updateAndDraw();
          editedImage.changes = true;
          break;
          
        case ('N'):                                                         // enlarge the selection
          if (activeWindow.equals(originalWindow)) {
            IJ.showMessage("Please work with the edited image.");
            originalImage.killRoi();
            IJ.selectWindow(title);
            return;
          }
        
          Roi smallRoi = editedImage.getRoi();                               // initial Roi specified by the user
          if (smallRoi != null) {
            double increment = 1.0;
            Roi newRoi = RoiEnlarger.enlarge(smallRoi, increment);
            editedImage.setRoi(newRoi);
          }
          
          break;
          
        case ('H'):                                                         // shrink the selection
          if (activeWindow.equals(originalWindow)) {
            IJ.showMessage("Please work with the edited image.");
            originalImage.killRoi();
            IJ.selectWindow(title);
            return;
          }
        
          Roi bigRoi = editedImage.getRoi();                               // initial Roi specified by the user
          if (bigRoi != null) {
            double increment = -1.0;
            Roi newRoi = RoiEnlarger.enlarge(bigRoi, increment);
            editedImage.setRoi(newRoi);
          }
          
          break;
          
        case ('E'):                                                         // erase this channel going forward or back
          if (activeWindow.equals(originalWindow) || channel == 4) {
            IJ.beep();
          }
          else {
            String color;
            if (channel == 1) {
              color = "red";
            }
            else if (channel == 2) {
              color = "green";
            }
            else {
              color = "blue";
            }
            GenericDialog gd = new GenericDialog("Choose Direction");
            String[] directions = {"forward to the end", "back to the beginning"};
            gd.addChoice("Erase the " + color + " channel from this time point", directions, directions[0]);
            gd.showDialog();
            if (gd.wasCanceled()) break;
            
            boolean forward = (gd.getNextChoice().equals(directions[0]));
            edited.setColor(Color.black);
            Roi roi;
            
            if (forward) {
              for (int t = frame; t <= frames; t++) {
                editedImage.setPositionWithoutUpdate(channel, 1, t);
                IJ.run("Select All");
                roi = editedImage.getRoi();
                edited.fill(roi);
                editedImage.killRoi();
              }
            }
            else {
              for (int t = frame; t >= 1; t--) {
                editedImage.setPositionWithoutUpdate(channel, 1, t);
                IJ.run("Select All");
                roi = editedImage.getRoi();
                edited.fill(roi);
                editedImage.killRoi();
              }
            }
            
            editedImage.setPosition(channel, 1, frame);
            editedImage.updateAndDraw();
            editedImage.changes = true;
          }
          break;
          
      }
      
    }
    
  //========================================================================================================================
    
    public void keyReleased(KeyEvent e) {}
    public void keyTyped(KeyEvent e) {}

    //========================================================================================================================
    
    public void imageClosed(ImagePlus imp) {
      originalWindow.removeKeyListener(this);
      originalCanvas.removeKeyListener(this);
      editedWindow.removeKeyListener(this);
      editedCanvas.removeKeyListener(this);
    }

    public void imageOpened(ImagePlus imp) {}
    
    public void imageUpdated(ImagePlus imp) {}
    
    //========================================================================================================================
    
    /* Updates both windows if one of the scroll bars is dragged. */
    public void adjustmentValueChanged(AdjustmentEvent e) {
      ImageWindow activeWindow = WindowManager.getCurrentWindow();
      ImagePlus activeImage = activeWindow.getImagePlus();
      int channel = activeImage.getChannel();
      int frame = activeImage.getFrame();
      
      // Ensure that the two windows have the same channel and frame.
      if (activeWindow.equals(originalWindow)) {
        editedImage.setPosition(channel, 1, frame);
      }
      else if (activeWindow.equals(editedWindow)) {
        originalImage.setPosition(channel, 1, frame);
      }
    }

    //========================================================================================================================
    
    /* Extracts an integer parameter from a parameter string in the Info property. */
    private int extractParameter(String parameterString) {
      int start = parameterString.indexOf(":") + 2;
      return Integer.parseInt(parameterString.substring(start));
    }
    
    //========================================================================================================================
    
    /* Traces a wand-type ROI around the spot that is within the specified ROI, if such a spot exists. */
    private PolygonRoi refineRoi(Roi roi) {
      editedImage.killRoi();
      editedImage.setRoi(roi);
      
      ImageStatistics stats = editedImage.getStatistics();
      int max = (int) stats.max;
      double channelMax = editedImage.getDisplayRangeMax();
      
      // Return null if there is no sufficiently bright spot within the Roi.
      if (channelMax == 0 || max < 0.25 * channelMax) {
        return null;
      }
      
      // Find a pixel that lies within the Roi and has the maximum value.
      Rectangle box = roi.getBounds();
      int x = 0, y = 0, xMax = 0, yMax = 0; 
      for (y = box.y; y < box.y + box.height; y++) {
        for (x = box.x; x < box.x + box.width; x++) {
          int value = edited.getPixel(x, y);
          if (roi.contains(x, y) && value == max) {
            xMax = x;
            yMax = y;
            break;
          }
        }
      }
      
      // Choose the tolerance for the wand. This choice is delicate, and crucial. The tolerance will be a 
      // fraction of the "max" value for the brightest spot in the Roi, where this fraction is allowed to range
      // from 0.25 for dim spots to 0.98 for bright spots. Empirically, if "max" is equal to the display
      // maximum value "channelMax", a fraction value of 0.5 is appropriate. The fraction increases by
      // "fractionIncrement" for every 2-fold increase in "max" relative to "channelMax". This algorithm works,
      // but the resulting Roi is too tight around the spot, so a final adjustment corrects the value of
      // "channelMax" by the factor of "wandAdjuster".
      channelMax = channelMax * wandAdjuster;
      double fraction = 0.5 + fractionIncrement * Math.log(max / channelMax) / Math.log(2.0);
      fraction = Math.max(fraction, 0.25);
      fraction = Math.min(fraction, 0.98);
      
      // Use a wand to outline the spot, starting at the pixel with the maximum value.
      Wand wand = new Wand(edited);
      wand.autoOutline(xMax, yMax, fraction * max, Wand.EIGHT_CONNECTED);
      
      return new PolygonRoi(wand.xpoints, wand.ypoints, wand.npoints, Roi.FREEROI);
    }
    
    //========================================================================================================================
    
    /* Calculates the x offset for moving an Roi to a new slice. The slice index starts at 0. */
    private int xOffset(int oldSlice, int newSlice) {
      int oldColumn = oldSlice % columns + 1;
      int newColumn = newSlice % columns + 1;
      return (newColumn - oldColumn) * (width + GAP);
    }
    
    //========================================================================================================================
    
    /* Calculates the y offset for moving an Roi to a new slice. The slice index starts at 0. */
    private int yOffset(int oldSlice, int newSlice) {
      int oldRow = oldSlice / columns + 1;
      int newRow = newSlice / columns + 1;
      return (newRow - oldRow) * (height + GAP);
    }
}

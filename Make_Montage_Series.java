package IJ_Plugins;

import ij.*;
import ij.plugin.PlugIn;
import ij.plugin.Duplicator;
import ij.process.*;
import ij.measure.*;
import ij.gui.GenericDialog;
import java.awt.*;

/* Converts a 4D hyperstack to a time series of 4-channel Z-stack montages, and autoscales the channel displays. */
public class Make_Montage_Series implements PlugIn {
  
	private ImagePlus originalImage, scaledImage;
	private ImageProcessor scaled;
	
	private int width, height, scaledWidth, scaledHeight, slices, frames, channels;   // Hyperstack parameters.
	private int first, last, trimmedSlices;                                           // User-chosen slices.
	private int maxWidth, maxHeight;                                                  // Maximum size of montage.
	private double scaleFactor;
	private boolean validScaleFactor = false;
	private int blackWindowWidth, blackWindowHeight, columns, rows;                   // Montage dimensions.
	private String title;
	
	private static final int XCORNER = 10, YCORNER = 100, GAP = 10, COLOR_ADJUST = 1000, GRAY_ADJUST = 75;
	
	//------------------------------------------------------------------------------------------------------------------------  
	
    public void run(String arg) {
      
      IJ.resetEscape();

      IJ.run("Open...");
      originalImage = IJ.getImage();
      title = originalImage.getTitle();
 
      if ( !(originalImage.getBitDepth() == 8)  || !(title.endsWith(".tif") || title.endsWith(".TIF")) || 
          !originalImage.isHyperStack() || !(originalImage.getNSlices() > 1) ) {
        IJ.showMessage("This plugin requires an 8-bit TIFF 4D hyperstack.");
        originalImage.close();
        return;
      }
      
      title = title.substring(0, title.length() - 4);           // Remove the extension.
      
      width = originalImage.getWidth();
      height = originalImage.getHeight();
      slices = originalImage.getNSlices();
      frames = originalImage.getNFrames();
      channels = originalImage.getNChannels();				   // "channels" indexes the 2 – 4 input channels.
      
      Dimension screen = IJ.getScreenSize();
      maxWidth = screen.width - 2 * XCORNER - 10;               // 5-px borders on each side of the image window.
      maxHeight = screen.height - YCORNER - XCORNER - 85;       // Takes into account the title bar and scroll bars.
      
      double provisionalScaleFactor = 4.0;                      // Default scale factor.
      first = 1;
      last = slices;
      
      while (!validScaleFactor) {
        GenericDialog gd = new GenericDialog("Parameters");
        gd.addNumericField("Scale Factor:", provisionalScaleFactor, 1);
        gd.addNumericField("First Slice:", first, 0);
        gd.addNumericField("Last Slice:", last, 0);
        gd.showDialog();
        if (gd.wasCanceled()) return;
        
        scaleFactor = gd.getNextNumber();
        scaledWidth = (int) Math.round(width * scaleFactor);
        scaledHeight = (int) Math.round(height * scaleFactor);
        
        first = (int) gd.getNextNumber();
        last = (int) gd.getNextNumber();
        if (first < 1 || last > slices || last < first) {
          IJ.showMessage("Those slice numbers are not valid.");
          originalImage.close();
          return;
        }
        trimmedSlices = last - first + 1;                        // Actual number of slices used in the montage.
        
        // Calculate how many scaled images will fit horizontally and vertically, separated by GAP pixels.
        columns = (int) Math.floor((double) (maxWidth - GAP) / (double) (scaledWidth + GAP));
        columns = Math.min(columns, trimmedSlices);
        blackWindowWidth = columns * (scaledWidth + GAP) + GAP;
        rows = (int) Math.ceil((double) trimmedSlices / (double) columns);
        blackWindowHeight = rows * (scaledHeight + GAP) + GAP;
        if (blackWindowHeight > maxHeight) {
          IJ.showMessage("The images will not fit on the screen with that scale factor.");
          provisionalScaleFactor = findMaxScaleFactor();
        }
        else {
          validScaleFactor = true;
        }
      }
      
      // Create a scaled version of the original image.
      scaledImage = new Duplicator().run(originalImage);
      scaled = scaledImage.getProcessor();
      
      originalImage.close();
         
      if (scaleFactor != 1.0) {
        scaled.setInterpolationMethod(ImageProcessor.BICUBIC);      // BILINEAR is faster, BICUBIC is better.
        StackProcessor sp = new StackProcessor(scaledImage.getStack(), scaled);
        ImageStack s2 = sp.resize(scaledWidth, scaledHeight);
        scaledImage.setStack(null, s2);
      }
      ImageStack scaledStack = scaledImage.getStack();
      
      // Make a black background window for the montage.
      CompositeImage compositeMontage = makeCompositeMontage(title, blackWindowWidth, blackWindowHeight, frames);
      ImageProcessor composite = compositeMontage.getProcessor();
      compositeMontage.show();
   
      int slice;
      ImageProcessor ipSlice;
      
      // Make composite montage.
      int xPos, yPos;
      int firstSlice = 0;
      for (int t = 1; t <= frames; t++) {
        for (int ch = 1; ch <= 4; ch++) {							// "ch" indexes the 4 output channels for the montage.
          if (IJ.escapePressed()) {
            compositeMontage.close();
            IJ.showStatus("Plugin aborted.");
            return;
          }
          compositeMontage.setPosition(ch, 1, t);
          
          if (channels == 2) {										// Green images only.
        	    if (ch != 1 && ch != 3) {                                // The red and blue output channels will not be used.
        	    	  if (ch == 2) {
            	    firstSlice = 2 * slices * (t - 1) + 1;  			    // First slice in a Z-stack for input channel 1 and time t.
            	  }
            	  else if (ch == 4) {
            	  	firstSlice = 2 * slices * (t - 1) + 2;				// Cell images are taken from input channel 2.
            	  }
        	    	  for (int z = first; z <= last; z++) {
        	        slice = firstSlice + 2 * (z - 1);
        	        ipSlice = scaledStack.getProcessor(slice);
        	        xPos = GAP + ((z - first) % columns) * (scaledWidth + GAP);
        	        yPos = GAP + ((z - first) / columns) * (scaledHeight + GAP);
        	        composite.insert(ipSlice, xPos, yPos);
        	      }
        	    }
          }
          
          else if (channels == 3) {									// Red and green images.
        	  	if (ch != 3) {											// The blue output channel will not be used.
        	  	  if (ch == 1 || ch == 2) {
        	  	    firstSlice = 3 * slices * (t - 1) + ch;  			// First slice in a Z-stack for input channel ch and time t.
        	  	  }
        	  	  else if (ch == 4) {
        	  	    firstSlice = 3 * slices * (t - 1) + 3;				// Cell images are taken from input channel 3.
        	  	  }
        	  	  for (int z = first; z <= last; z++) {
        	        slice = firstSlice + 3 * (z - 1);
        	        ipSlice = scaledStack.getProcessor(slice);
        	        xPos = GAP + ((z - first) % columns) * (scaledWidth + GAP);
        	        yPos = GAP + ((z - first) / columns) * (scaledHeight + GAP);
        	        composite.insert(ipSlice, xPos, yPos);
        	      }
        	  	}
          }
          
          else if (channels == 4) {									// Red, green, and blue images. All output channels will be used.
        	    firstSlice = 4 * slices * (t - 1) + ch;					// First slice in a Z-stack for input channel ch and time t.
        	    for (int z = first; z <= last; z++) {
        	      slice = firstSlice + 4 * (z - 1);
        	      ipSlice = scaledStack.getProcessor(slice);
        	      xPos = GAP + ((z - first) % columns) * (scaledWidth + GAP);
        	      yPos = GAP + ((z - first) / columns) * (scaledHeight + GAP);
        	      composite.insert(ipSlice, xPos, yPos);
        	    }
          }
          
        }
      }
      
      scaledImage.close();
      
      if (channels == 4) {											// Set custom blue color for 3-color movies.
    	    Color myBlue = new Color(0, 96, 255);
    	    LUT blueLUT = LUT.createLutFromColor(myBlue);
    	    compositeMontage.setChannelLut(blueLUT, 3);
      }
                                 
      for (int ch = 1; ch <= 4; ch++) {
    	    if ( (channels == 2 && ch != 1 && ch != 3) || (channels == 3 && ch != 3) || (channels == 4) ) {
          compositeMontage.setPosition(ch,1,1);
          channelAdjust(compositeMontage, ch == 4);					// Adjust the fluorescence or gray threshold.
          compositeMontage.updateAndDraw();
    	    }
      }
      
      compositeMontage.setPosition(1,1,1);
      
      // Record image data that will be used later to regenerate a hyperstack.
      String compositeMontageInfo =     "slices: " + trimmedSlices + "\n" + 
                                        "columns: " + columns + "\n" +
                                        "channels: " + channels + "\n" +
                                        "GAP: " + GAP + "\n" +
                                        "slice width: " + scaledWidth + "\n" +
                                        "slice height: " + scaledHeight;
      compositeMontage.setProperty("Info", compositeMontageInfo);
      
      compositeMontage.changes = true;
      
	}

    //========================================================================================================================

    /* Creates a black hyperstack composite image window for displaying a multi-channel montage time series. */
    private CompositeImage makeCompositeMontage(String title, int windowWidth, int windowHeight, int frames) {
    	  int montageChannels = 4;		// red, green, blue, gray
      ImagePlus blackImage = IJ.createImage(null, "8-bit black", windowWidth, windowHeight, 1);
      ImageProcessor black = blackImage.getProcessor();
      
      ImageStack xycztStack = new ImageStack(windowWidth, windowHeight);
      for (int ch = 1; ch <= montageChannels; ch++) {
        for (int t = 1; t <= frames; t++) {
          xycztStack.addSlice(null, black.duplicate());
        }
      }
      blackImage.close();
      
      ImagePlus montage = new ImagePlus(title + " Montage.tif", xycztStack);
      montage.setDimensions(montageChannels, 1, frames);
      CompositeImage compositeMontage = new CompositeImage(montage, CompositeImage.COMPOSITE);
      compositeMontage.setOpenAsHyperStack(true);
      montage.close();
      
      return compositeMontage;
    }
    
    //========================================================================================================================
    
    /* Automatically adjusts the threshold for the currently selected channel of the image. */
    private void channelAdjust(ImagePlus imp, boolean gray) {
      Calibration cal = imp.getCalibration();
      imp.setCalibration(null);
      ImageStatistics stats = imp.getStatistics();          	// Get uncalibrated statistics.
      imp.setCalibration(cal);
      
      if (gray) {
        // The contrast is reduced by extending the range of minimum and maximum brightness values beyond the normal 0-255 range.
        // Increasing GRAY_ADJUST makes the gray images darker and smoother.
    	    imp.setDisplayRange(-GRAY_ADJUST, 10 * GRAY_ADJUST);
      }
      else {
        // The display maximum value is set to the highest number at which the fraction of pixels having at least that
        // value exceeds 1/COLOR_ADJUST. Lowering COLOR_ADJUST results in more saturated pixels.
    	    int cutoff = stats.pixelCount/COLOR_ADJUST;
    	    int[] histogram = stats.histogram;
        boolean found = false;
        int i = 256;
        int sum = 0;
        while (!found) {
          i--;
          sum += histogram[i];
          found = (sum > cutoff) || (i == 1);
        }
        imp.setDisplayRange(0, i);
      }
      
    }
    
    //========================================================================================================================
    
    /* Determine how large the scale factor can be without exceeding the screen size. */
    private double findMaxScaleFactor() {
      double testScaleFactor;
      for (int i = 40; i >= 10; i--) {
        testScaleFactor = i / 10.0;
        scaledWidth = (int) Math.round(width * testScaleFactor);
        scaledHeight = (int) Math.round(height * testScaleFactor);
        
        // Calculate how many scaled images will fit horizontally and vertically, separated by GAP pixels.
        columns = (int) Math.floor((double) (maxWidth - GAP) / (double) (scaledWidth + GAP));
        columns = Math.min(columns, trimmedSlices);
        blackWindowWidth = columns * (scaledWidth + GAP) + GAP;
        rows = (int) Math.ceil((double) trimmedSlices / (double) columns);
        blackWindowHeight = rows * (scaledHeight + GAP) + GAP;
        if (blackWindowHeight <= maxHeight) {
          return testScaleFactor;
        }
      }
      
      return 1.0;                                           // Fallback option.
    }
    
}

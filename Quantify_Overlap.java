package IJ_Plugins;

import ij.*;
import ij.plugin.*;
import ij.process.*;
import ij.text.TextWindow;
import ij.measure.*;
import ij.gui.*;
import java.awt.Color;
import java.awt.Window;

import ij.plugin.frame.RoiManager;

/* Quantifies the overlap between red and green signals in a selected ROI of a TIFF or ICS image or Z-stack, optionally ignoring
 * areas that correspond to structures in the blue channel.  
 * 
 * Accurate alignment of the channels is essential. A channel that will serve as a reference should be deconvolved. A channel that
 * has signal to be measured can contain either raw or deconvolved data. The blue channel (if present) should be deconvolved. */

public class Quantify_Overlap implements PlugIn {
  
	private ImagePlus image, roiImage, imageCopy, binaryImage, invertedBinaryImage, subtractedBinaryImage, redInGreen, greenInRed;
	private ImageProcessor binary;
	private short[] pixels;
	private String title, options, processedChoice, redThreshold, greenThreshold, blueThreshold, outputChoice, roiName;
	private boolean measureGreen, measureRed, ignoreBlue, showImages, leaveOpen, fraction;
	private boolean manager = false;
	private int channels, slices, time, zPosition, bitDepth, width, height, adjustment;
	private int rearrangeChannels = 0;                                           // The assumed default order is RGB.
	private double total, overlapping, subtractedRed, subtractedGreen;
	private Roi roi, roiClone;
	private Duplicator dup = new Duplicator();
	private String version = "5.10 (05-24-2024)";
	
	//------------------------------------------------------------------------------------------------------------------------  
	
    public void run(String arg) {

      image = IJ.getImage();
      title = image.getTitle(); 
      if ( !(title.endsWith(".tif") || title.endsWith(".TIF") || title.endsWith(".ics") || title.endsWith(".ICS")) ) {
        IJ.showMessage("This plugin requires an image or Z-stack in TIFF or ICS format.");
        return;
      }
      channels = image.getNChannels();
      slices = image.getNSlices();
      time = image.getT();
      zPosition = image.getZ();
      bitDepth = image.getBitDepth();
      
      roi = image.getRoi();
      if (roi == null) {
        IJ.showMessage("Before running this plugin, please select the object to be quantified.");
        IJ.selectWindow(title);
        return;
      }
      
      RoiManager rm = RoiManager.getInstance();
      if (rm != null) {
        int index = rm.getSelectedIndex();
        if (index != -1) {
          manager = true;                                                      // The ROI was selected using an ROI manager.
        }
        roiName = rm.getName(index);
      }

      // Close any processed image windows with generic names from the previous iteration.
      if (WindowManager.getWindow("ROI") instanceof Window) {
        ImagePlus img = WindowManager.getImage("ROI");
        img.changes = false;
        img.close();
      }
      if (WindowManager.getWindow("Binary Mask") instanceof Window) {
        ImagePlus img = WindowManager.getImage("Binary Mask");
        img.changes = false;
        img.close();
      }
      if (WindowManager.getWindow("Subtracted Mask") instanceof Window) {
        ImagePlus img = WindowManager.getImage("Subtracted Mask");
        img.changes = false;
        img.close();
      }
      if (WindowManager.getWindow("Green in Red") instanceof Window) {
        ImagePlus img = WindowManager.getImage("Green in Red");
        img.changes = false;
        img.close();
      }
      if (WindowManager.getWindow("Red in Green") instanceof Window) {
        ImagePlus img = WindowManager.getImage("Red in Green");
        img.changes = false;
        img.close();
      }
      
      // If there is no existing Log window, show the parameters dialog. Otherwise re-use the existing parameters.
      options = IJ.getLog();
      if ( (options == null) || !options.contains("Quantify Overlap") ) {
        GenericDialog gd = new GenericDialog("Measurement Options");
        gd.addCheckbox("Measure green overlapping with red", true);
        gd.addCheckbox("Measure red overlapping with green", true);
        gd.addCheckbox("Ignore signal that overlaps blue structures", false);
        String[] processed = {"Hide", "Show for this measurement only", "Show and leave open"};
        gd.addMessage("----------------------------------------------------     ");
        gd.addChoice("Processed images:", processed, processed[0]);
        String[] channelOrder = {"Red-Green  OR  Red-Green-Blue", "Blue-Red-Green", "Green-Blue-Red"};
        gd.addChoice("Channel order: ", channelOrder, channelOrder[0]);
        gd.addMessage("----------------------------------------------------     ");
        String[] thresholdLevel = {"Basement", "Lowest", "Lower", "Low", "Medium", "High", "Higher", "Highest"};
        gd.addChoice("         Red threshold level: ", thresholdLevel, thresholdLevel[4]);
        gd.addChoice("       Green threshold level: ", thresholdLevel, thresholdLevel[4]);
        gd.addChoice("        Blue threshold level: ", thresholdLevel, thresholdLevel[4]);
        gd.addMessage("----------------------------------------------------     ");
        String[] output = {"The fraction of the signal", "The absolute amount of signal"};
        gd.addChoice("Output: ", output, output[0]);
        gd.addMessage("----------------------------------------------------     ");
        gd.addMessage("  To reset these parameters, close the \"Log\" window.");
        
        gd.showDialog();
        if (gd.wasCanceled()) return;

        measureGreen = gd.getNextBoolean();
        measureRed = gd.getNextBoolean();
        ignoreBlue = gd.getNextBoolean();
        processedChoice = gd.getNextChoice();
        if (processedChoice.equals(processed[0])) {
          showImages = false;
          leaveOpen = false;
        }
        else if (processedChoice.equals(processed[1])) {
          showImages = true;
          leaveOpen = false;
        }
        else {
          showImages = true;
          leaveOpen = true;
        }
        String order = gd.getNextChoice();
        if (order.equals(channelOrder[1])) {
          rearrangeChannels = 1;
        }
        else if (order.equals(channelOrder[2])) {
          rearrangeChannels = 2;
        }
        redThreshold = gd.getNextChoice();
        greenThreshold = gd.getNextChoice();
        blueThreshold = gd.getNextChoice();
        
        if (!measureGreen && !measureRed) {
          IJ.showMessage("You must measure either green or red.");
          IJ.selectWindow(title);
          return;
        }
        
        outputChoice = gd.getNextChoice();
        if (outputChoice.equals(output[0])) {
          fraction = true;
        }
        else {
          fraction = false;
        }
        
        options = "Version " + version + "\n";
        options += "To reset the parameters for the Quantify Overlap plugin, close this window.\n\n";
        if (measureGreen) {
          options += "Measure Green\n";
        }
        if (measureRed)
        {
          options += "Measure Red\n";
        }
        if (ignoreBlue) {
          options += "Ignore Blue\n";
        }
        options += "\n";
        options += "Processed Images: " + processedChoice + "\n";
        if (rearrangeChannels == 1) {
          options += "Channel Order: BRG\n";
        }
        else if (rearrangeChannels == 2) {
          options += "Channel Order: GBR\n";
        }
        options += "\n";
        options += "Red Threshold: " + redThreshold + "\n";
        options += "Green Threshold: " + greenThreshold + "\n";
        options += "Blue Threshold: " + blueThreshold + "\n";
        options += "\n";
        if (fraction) {
          options += "Output: Fraction of the signal";
        }
        else {
          options += "Output: Absolute amount of signal";
        }
        
        // Write the parameters to the Log window.
        IJ.log("\\Clear");
        IJ.log(options);
      }
      else {
        measureGreen = options.contains("Measure Green");
        measureRed = options.contains("Measure Red");
        ignoreBlue = options.contains("Ignore Blue");
        showImages = options.contains("Show");
        leaveOpen = (options.contains("Show") && !options.contains("measurement"));
        if (options.contains("Channel Order: BRG")) {
          rearrangeChannels = 1;
        }
        else if (options.contains("Channel Order: GBR")) {
          rearrangeChannels = 2;
        }
        if (options.contains("Red Threshold: Basement")) {
          redThreshold = "Basement";
        }
        else if (options.contains("Red Threshold: Lowest")) {
          redThreshold = "Lowest";
        }
        else if (options.contains("Red Threshold: Lower")) {
          redThreshold = "Lower";
        }
        else if (options.contains("Red Threshold: Low")) {
          redThreshold = "Low";
        }
        else if (options.contains("Red Threshold: Medium")) {
          redThreshold = "Medium";
        }
        else if (options.contains("Red Threshold: Highest")) {
          redThreshold = "Highest";
        }
        else if (options.contains("Red Threshold: Higher")) {
          redThreshold = "Higher";
        }
        else {
          redThreshold = "High";
        }
        if (options.contains("Green Threshold: Basement")) {
          greenThreshold = "Basement";
        }
        else if (options.contains("Green Threshold: Lowest")) {
          greenThreshold = "Lowest";
        }
        else if (options.contains("Green Threshold: Lower")) {
          greenThreshold = "Lower";
        }
        else if (options.contains("Green Threshold: Low")) {
          greenThreshold = "Low";
        }
        else if (options.contains("Green Threshold: Medium")) {
          greenThreshold = "Medium";
        }
        else if (options.contains("Green Threshold: Highest")) {
          greenThreshold = "Highest";
        }
        else if (options.contains("Green Threshold: Higher")) {
          greenThreshold = "Higher";
        }
        else {
          greenThreshold = "High";
        }
        if (options.contains("Blue Threshold: Basement")) {
          blueThreshold = "Basement";
        }
        else if (options.contains("Blue Threshold: Lowest")) {
          blueThreshold = "Lowest";
        }
        else if (options.contains("Blue Threshold: Lower")) {
          blueThreshold = "Lower";
        }
        else if (options.contains("Blue Threshold: Low")) {
          blueThreshold = "Low";
        }
        else if (options.contains("Blue Threshold: Medium")) {
          blueThreshold = "Medium";
        }
        else if (options.contains("Blue Threshold: Highest")) {
          blueThreshold = "Highest";
        }
        else if (options.contains("Blue Threshold: Higher")) {
          blueThreshold = "Higher";
        }
        else {
          blueThreshold = "High";
        }
        fraction = options.contains("Fraction");
      }
       
      // Copy the image, convert to 16-bit if needed, and make a new image from the ROI.
      image.killRoi();
      imageCopy = dup.run(image);
      if (bitDepth != 16) {
        imageCopy = convertToSixteenBit(dup.run(imageCopy), bitDepth);
      }
      image.setRoi(roi);
      imageCopy.setRoi(roi);
      imageCopy.setTitle("Image Copy");
      
      roiImage = dup.run(imageCopy, 1, channels, 1, slices, time, time);
      roiImage.setTitle("ROI");
      width = roiImage.getWidth();
      height = roiImage.getHeight();

      imageCopy.killRoi();
      imageCopy.setDisplayMode(IJ.GRAYSCALE);
      roiImage.setDisplayMode(IJ.GRAYSCALE);
      
      // If necessary, rearrange the channels so that the first three are RGB.
      if (rearrangeChannels == 1) {
        int[] newOrder = {2,3,1,4};
        imageCopy = ChannelArranger.run(imageCopy, newOrder);
        roiImage = ChannelArranger.run(roiImage, newOrder);
      }
      else if (rearrangeChannels == 2) {
        int[] newOrder = {3,1,2,4};
        imageCopy = ChannelArranger.run(imageCopy, newOrder);
        roiImage = ChannelArranger.run(roiImage, newOrder);
      }
      
      // Clear outside the ROI if it's not rectangular.
      roiClone = (Roi) roi.clone();
      roiClone.setLocation(0, 0);
      roiImage.setRoi(roiClone);
      Toolbar.setBackgroundColor(Color.black);
      IJ.run(roiImage, "Clear Outside", "stack");

      // Make a binary version of the ROI image, and an inverted binary version of the ROI image for use if blue structures
      // will be ignored. For each channel, an adjustment removes low-intensity signal to leave clean structures.
      roiImage.killRoi();
      binaryImage = dup.run(roiImage);
      binaryImage.setTitle("Binary Mask");
      binary = binaryImage.getProcessor();
      
      // Make a binary version of the red channel.
      adjustment = (int) findThreshold(imageCopy, 1, slices, redThreshold);             // Remove a bottom fraction of the pixel data.   
      for (int z = 1; z <= slices; z++) {
        binaryImage.setPositionWithoutUpdate(1, z, 1);
        IJ.run(binaryImage, "Subtract...", "value=" + adjustment);
        pixels = (short[]) binary.getPixels();
        for (int i = 0; i < width * height; i++) {
          if ((pixels[i] & 0xFFFF) > 0) {
            pixels[i] = (short) 65535;
          }
        }
      }
      
      // Make a binary version of the green channel.
      adjustment = (int) findThreshold(imageCopy, 2, slices, greenThreshold);            // Remove a bottom fraction of the pixel data.
      for (int z = 1; z <= slices; z++) {
        binaryImage.setPositionWithoutUpdate(2, z, 1);
        IJ.run(binaryImage, "Subtract...", "value=" + adjustment);
        pixels = (short[]) binary.getPixels();
        for (int i = 0; i < width * height; i++) {
          if ((pixels[i] & 0xFFFF) > 0) {
            pixels[i] = (short) 65535;
          }
        }
      }
      
      // If the blue channel is being used for subtraction, make a binary version of the blue channel.
      if (ignoreBlue) {
        adjustment = (int) findThreshold(imageCopy, 3, slices, blueThreshold);            // Remove a bottom fraction of the pixel data.
        for (int z = 1; z <= slices; z++) {
          binaryImage.setPositionWithoutUpdate(3, z, 1);
          IJ.run(binaryImage, "Subtract...", "value=" + adjustment);
          pixels = (short[]) binary.getPixels();
          for (int i = 0; i < width * height; i++) {
            if ((pixels[i] & 0xFFFF) > 0) {
              pixels[i] = (short) 65535;
            }
          }
        }
      }
      
      invertedBinaryImage = dup.run(binaryImage);
      IJ.run(invertedBinaryImage, "Invert", "stack");
      
      ImageCalculator ic = new ImageCalculator();
      
      // Create a temporary image to hold the fluorescence data for quantification.
      ImagePlus temp = IJ.createImage("temp", "16-bit black", width, height, 1);
      
      // If the blue channel is being used, create a subtracted binary image and measure how much of each mask was subtracted.
      if (ignoreBlue) {
        subtractedBinaryImage = dup.run(binaryImage);
        subtractedBinaryImage.setTitle("Subtracted Mask");
        invertedBinaryImage.setC(3);
        subtractedBinaryImage.setC(1);
        for (int z = 1; z <= slices; z++) {
          subtractedBinaryImage.setZ(z);
          invertedBinaryImage.setZ(z);
          ic.run("AND", subtractedBinaryImage, invertedBinaryImage);
        }
        subtractedBinaryImage.setC(2);
        for (int z = 1; z <= slices; z++) {
          subtractedBinaryImage.setZ(z);
          invertedBinaryImage.setZ(z);
          ic.run("AND", subtractedBinaryImage, invertedBinaryImage);
        }
        
        // Measure how much of the red mask was subtracted.
        total = 0.0;
        overlapping = 0.0;
        binaryImage.setC(1);
        subtractedBinaryImage.setC(1);
        for (int z = 1; z <= slices; z++) {
          binaryImage.setZ(z);
          temp.getProcessor().copyBits(binaryImage.getProcessor(), 0, 0, Blitter.COPY);
          total += temp.getStatistics().mean;
          subtractedBinaryImage.setZ(z);
          temp.getProcessor().copyBits(subtractedBinaryImage.getProcessor(), 0, 0, Blitter.COPY);
          overlapping += temp.getStatistics().mean;
          subtractedRed = 100.0 * (1.0 - overlapping / total);
        }
        
        // Measure how much of the green mask was subtracted.
        total = 0.0;
        overlapping = 0.0;
        binaryImage.setC(2);
        subtractedBinaryImage.setC(2);
        for (int z = 1; z <= slices; z++) {
          binaryImage.setZ(z);
          temp.getProcessor().copyBits(binaryImage.getProcessor(), 0, 0, Blitter.COPY);
          total += temp.getStatistics().mean;
          subtractedBinaryImage.setZ(z);
          temp.getProcessor().copyBits(subtractedBinaryImage.getProcessor(), 0, 0, Blitter.COPY);
          overlapping += temp.getStatistics().mean;
          subtractedGreen = 100.0 * (1.0 - overlapping / total);
        }

      }
            
      // Create the greenInRed and/or redInGreen images. If desired, remove any signal that overlaps with blue structures.
      if (measureGreen) {
        greenInRed = dup.run(roiImage);
        greenInRed.setTitle("Green in Red");
        greenInRed.setC(2);
        binaryImage.setC(1);
        invertedBinaryImage.setC(3);
        for (int z = 1; z <= slices; z++) {
          greenInRed.setZ(z);
          binaryImage.setZ(z);
          ic.run("AND", greenInRed, binaryImage);
          if (ignoreBlue) {
            invertedBinaryImage.setZ(z);
            ic.run("AND", greenInRed, invertedBinaryImage);
          }
        }
      }
      
      if (measureRed) {
        redInGreen = dup.run(roiImage);
        redInGreen.setTitle("Red in Green");
        redInGreen.setC(1);
        binaryImage.setC(2);
        invertedBinaryImage.setC(3);
        for (int z = 1; z <= slices; z++) {
          redInGreen.setZ(z);
          binaryImage.setZ(z);
          ic.run("AND", redInGreen, binaryImage);
          if (ignoreBlue) {
            invertedBinaryImage.setZ(z);
            ic.run("AND", redInGreen, invertedBinaryImage);
          }
        }
      }
      
      invertedBinaryImage.close();
      
      // Create a ResultsTable for the output.
      ResultsTable results = getOrCreateResultsTable("Overlap");
      results.setPrecision(3);
      results.incrementCounter();
      results.addValue("Image", title);
      if (manager) {
        results.addValue("ROI", roiName);
      }
      
      // Measure the green signal that overlaps the red mask.
      if (measureGreen) {
        total = 0.0;
        overlapping = 0.0;
        roiImage.setC(2);
        greenInRed.setC(2);
        for (int z = 1; z <= slices; z++) {
          roiImage.setZ(z);
          temp.getProcessor().copyBits(roiImage.getProcessor(), 0, 0, Blitter.COPY);
          total += temp.getStatistics().mean;
          greenInRed.setZ(z);
          temp.getProcessor().copyBits(greenInRed.getProcessor(), 0, 0, Blitter.COPY);
          overlapping += temp.getStatistics().mean;
        }
        if (fraction) {
          results.addValue("Green in Red", overlapping / total);
        }
        else {
          results.addValue("Green in Red", overlapping);
        }
        if (ignoreBlue) {
          results.addValue("Subtracted Red %", subtractedRed);
        }
      }
      
      // Measure the red signal that overlaps the green mask.
      if (measureRed) {
        total = 0.0;
        overlapping = 0.0;
        roiImage.setC(1);
        redInGreen.setC(1);
        for (int z = 1; z <= slices; z++) {
          roiImage.setZ(z);
          temp.getProcessor().copyBits(roiImage.getProcessor(), 0, 0, Blitter.COPY);
          total += temp.getStatistics().mean;
          redInGreen.setZ(z);
          temp.getProcessor().copyBits(redInGreen.getProcessor(), 0, 0, Blitter.COPY);
          overlapping += temp.getStatistics().mean;
        }
        if (fraction) {
          results.addValue("Red in Green", overlapping / total);
        }
        else {
          results.addValue("Red in Green", overlapping);
        }
        if (ignoreBlue) {
          results.addValue("Subtracted Green %", subtractedGreen);
        }
      }
      
      temp.close();
      image.setZ(zPosition);
      results.show("Overlap");
      
      if (leaveOpen) {
        roiImage.setTitle("ROI – " + title);
        binaryImage.setTitle("Binary Mask – " + title);
        if (ignoreBlue) {
          subtractedBinaryImage.setTitle("Subtracted Mask – " + title);
        }
        if (measureGreen) {
          greenInRed.setTitle("Green in Red – " + title);
        }
        if (measureRed) {
          redInGreen.setTitle("Red in Green – " + title);
        }
      }
      
      if (showImages) {
        roiImage.setZ(1);
        roiImage.show();
        roiImage.changes = false;
        binaryImage.setZ(1);
        binaryImage.show();
        binaryImage.changes = false;
        if (ignoreBlue) {
          subtractedBinaryImage.setZ(1);
          subtractedBinaryImage.show();
          subtractedBinaryImage.changes = false;
        }
        if (measureGreen) {
          greenInRed.setZ(1);
          greenInRed.show();
          greenInRed.changes = false;
        }
        if (measureRed) {
          redInGreen.setZ(1);
          redInGreen.show();
          redInGreen.changes = false;
        }
      }
      else {
        roiImage.close();
        binaryImage.close();
        if (measureGreen) {
          greenInRed.close();
        }
        if (measureRed) {
          redInGreen.close();
        }
      }

      IJ.selectWindow(title);

    }
      
    //========================================================================================================================
      
    public static ResultsTable getOrCreateResultsTable(String title) {
      Window win = WindowManager.getWindow(title);
      if (win instanceof TextWindow) {
          TextWindow rt_exist = (TextWindow) win;
          return rt_exist.getTextPanel().getOrCreateResultsTable();
      }
      return new ResultsTable();
    }   
      
    //========================================================================================================================
      
    /* Finds a threshold value that will be subtracted to remove unwanted low-intensity pixels to create a binary image. */
    private double findThreshold(ImagePlus imp, int channel, int stackSize, String colorThreshold) {
      ImagePlus blurredImage = dup.run(imp);
      ImageProcessor ip = blurredImage.getProcessor();
      int max = 0, brightestSlice = 1;
      double brightness = 0.0, brightest = 0.0;
      
      for (int z = 1; z <= stackSize; z++) {
        blurredImage.setPositionWithoutUpdate(channel, z, 1);
        brightness = ip.getStatistics().mean - ip.getStatistics().median;     // Subtracting the median removes slices with high background.      
        if (brightness > brightest) {
          brightest = brightness;
          brightestSlice = z;
        }
      }

      blurredImage.setPositionWithoutUpdate(channel, brightestSlice, 1);
      IJ.run(blurredImage, "Gaussian Blur...", "sigma=2 slice");
      max = (int) ip.getStats().max;                                // Maximum value in the brightest slice after blurring.
      double interval = (double) max / 256.0;
      
      /*
      ResultsTable output = getOrCreateResultsTable("Curve for Channel " + channel);
      output.setPrecision(3);
      double[] subtracted = new double[256];
      for (int i = 0; i < 256; i++) {
        subtracted[i] = ip.getStats().mean;
        IJ.run(blurredImage, "Subtract...", "value=" + interval);
        output.addValue("i", i);
        output.addValue("Mean", subtracted[i]);
        output.incrementCounter();
      }
      output.show("Curve for Channel " + channel);
      */
      
      // Progressively subtract 1/256 of the maximum value from the image, then plot the mean values to get an exponential decline.
      double[] xValues = new double[256], yValues = new double[256];
      for (int i = 0; i < 256; i++) {
        xValues[i] = (double) i;
        yValues[i] = ip.getStats().mean;
        IJ.run(blurredImage, "Subtract...", "value=" + interval);
      }
      CurveFitter curve = new CurveFitter(xValues, yValues);
      curve.doFit(4);                                               // Exponential
      double[] params = curve.getParams();
      double b = params[1];                                         // params[1] is b in the equation y = a*exp(b*x)
      
      double val;                                                   // Empirically determined values that give suitable thresholds.
      if (colorThreshold.equals("Basement")) {
        val = -0.0200;
      }
      else if (colorThreshold.equals("Lowest")) {
        val = -0.0150;
      }
      else if (colorThreshold.equals("Lower")) {
        val = -0.0125;
      }
      else if (colorThreshold.equals("Low")) {
        val = -0.0100;
      }
      else if (colorThreshold.equals("Medium")) {
        val = -0.0075;
      }
      else if (colorThreshold.equals("High")) {
        val = -0.0050;
      }
      else if (colorThreshold.equals("Higher")) {
        val = -0.0035;
      }
      else {
        val = -0.0020;
      }
      
      // The threshold is the location on the exponential curve where the slope reaches "val".
      double threshold = (interval / b) * Math.log(val / b);
      threshold = Math.max(0.0, threshold);
      
      return threshold;
    }
    
    //========================================================================================================================
    
    /* Converts an image to 16-bit. */
    private ImagePlus convertToSixteenBit(ImagePlus imp, int bits) {
      ImagePlus newImage;
      int impWidth = imp.getWidth();
      int impHeight = imp.getHeight();
      
      if (bits == 8) {
        IJ.run(imp, "16-bit", "");
        IJ.run(imp, "Multiply...", "value=127 stack");
        newImage = imp;
      }
      
      else {  // (bits == 32)
        newImage = IJ.createHyperStack("", impWidth, impHeight, channels, slices, 1, 16);
        float[] pixels32 = new float[impWidth * impHeight];
        short[] pixels16 = new short[impWidth * impHeight];
        // For each channel, find the minimum and maximum values in the stack.
        for (int c = 1; c <= channels; c++) {
          imp.setC(c);
          newImage.setC(c);
          double min = 0.0, max = 0.0;
          for (int z = 1; z <=slices; z++) {
            imp.setPositionWithoutUpdate(c, z, 1);
            if (z == 1) {
              min = imp.getProcessor().getStats().min;
              max = imp.getProcessor().getStats().max;
            }
            else {
              min = Math.min(min, imp.getProcessor().getStats().min);
              max = Math.max(max, imp.getProcessor().getStats().max);
            }
          }
          double scale;
          if ((max - min) == 0.0) {
              scale = 1.0;
          }
          else {
              scale = 65535.0/(max - min);
          }
          // Scale the 16-bit values for this channel, and place the scaled values in newImage.
          double value;
          for (int z = 1; z <= slices; z++) {
            imp.setZ(z);
            pixels32 = (float[]) imp.getProcessor().getPixelsCopy();
            for (int i = 0; i < impWidth * impHeight; i++) {
              value = (pixels32[i] - min) * scale;
              if (value < 0.0) {
                value = 0.0;
              }
              if (value > 65535.0) {
                value = 65535.0;
              }
              pixels16[i] = (short) (value + 0.5);
            }
            newImage.setZ(z);
            short[] pixels16Copy = new short[impWidth * impHeight];
            System.arraycopy(pixels16, 0, pixels16Copy, 0, impWidth * impHeight);
            newImage.getProcessor().setPixels(pixels16Copy);
          }
        }
      }
      
      return newImage;
    }
    
	}


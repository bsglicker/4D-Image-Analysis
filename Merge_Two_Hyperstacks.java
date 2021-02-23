package IJ_Plugins;

import java.awt.Color;

import ij.*;
import ij.plugin.PlugIn;
import ij.process.*;
import ij.gui.*;

/* Merges two 4-channel hyperstacks, either to add the fluorescence, or to place one above the other. */
public class Merge_Two_Hyperstacks implements PlugIn {
  
	private ImagePlus firstImage, secondImage, mergedImage;
	private CompositeImage compositeMergedImage;
	private ImageProcessor merged;
	private int width, height, channels, slices, frames, images;                                // Hyperstack parameters.
  private double redMax, greenMax, blueMax, grayMax, grayMin;
	private boolean mergeFluorescence = false;
	private String title, info;
	private static final int GAP = 10;
	
	//------------------------------------------------------------------------------------------------------------------------  
	
    public void run(String arg) {
      
      IJ.resetEscape();
      
      GenericDialog gd = new GenericDialog("Choose Merge Option");
      String[] mergeOptions = {"Merge fluorescence signals", "Place first above second"};
      gd.addChoice("Merge Option:", mergeOptions, mergeOptions[0]);
      gd.addMessage("\nThe next step is to open the two 4D hyperstacks.");
      gd.showDialog();
      if (gd.wasCanceled()) return;
      
      String mergeOption = gd.getNextChoice();
      if (mergeOption.equals(mergeOptions[0])) {
        mergeFluorescence = true;
      }

      // Open the first hyperstack.
      IJ.run("Open...");
      firstImage = IJ.getImage();
      title = firstImage.getTitle();
      if ( !(firstImage.getBitDepth() == 8)  || !(title.endsWith(".tif") || title.endsWith(".TIF")) || 
          !firstImage.isHyperStack() || !(firstImage.getNSlices() > 1) || !(firstImage.getNChannels() == 4) ) {
        IJ.showMessage("This plugin requires a 4-channel 8-bit RGB TIFF 4D hyperstack.");
        firstImage.close();
        return;
      }
      
      width = firstImage.getWidth();
      height = firstImage.getHeight();
      slices = firstImage.getNSlices();
      frames = firstImage.getNFrames();
      images = 4 * slices * frames;
      
      info = (String) firstImage.getProperty("Info");
      int start = info.indexOf(":") + 2;
      channels = Integer.parseInt(info.substring(start));               // Number of channels in the original movie.
      
      // Open the second hyperstack.
      IJ.run("Open...");
      secondImage = IJ.getImage();
      title = secondImage.getTitle();
      if ( !(secondImage.getBitDepth() == 8)  || !(title.endsWith(".tif") || title.endsWith(".TIF")) || 
          !secondImage.isHyperStack() || !(secondImage.getNSlices() > 1) || !(secondImage.getNChannels() == 4) ) {
        IJ.showMessage("This plugin requires a 4-channel 8-bit RGB TIFF 4D hyperstack.");
        firstImage.close();
        secondImage.close();
        return;
      }
      
      // Ensure that the two hyperstacks are matched.
      if ( !(secondImage.getWidth() == width) || !(secondImage.getHeight() == height) ||
           !(secondImage.getNSlices() == slices) || !(secondImage.getNFrames() == frames) ) {
             IJ.showMessage("The two hyperstacks must be matched.");
             firstImage.close();
             secondImage.close();
             return;
           }
      
      // Create the merged image.
      int mergedHeight;
      if (mergeFluorescence) { 
        mergedHeight = height;
      }
      else {
        mergedHeight = 2 * height + GAP;
      }
      mergedImage = IJ.createImage("Merged.tif", "8-bit black", width, mergedHeight, images);
      mergedImage.setDimensions(4, slices, frames);
      compositeMergedImage = new CompositeImage(mergedImage, CompositeImage.COMPOSITE);
      mergedImage.close();
      compositeMergedImage.setOpenAsHyperStack(true);
      merged = compositeMergedImage.getProcessor();
      
      int slice;
      ImageProcessor ipSlice;
      ImageStack firstStack = firstImage.getStack();
      ImageStack secondStack = secondImage.getStack();

      for (int ch = 1; ch <= 4; ch++) {
        for (int t = 1; t <= frames; t++) {
          int firstSlice = 4 * slices * (t - 1) + ch;                   // First slice in a Z-stack for channel ch and time t.
          for (int z = 1; z <= slices; z++) {
            if (IJ.escapePressed()) {
              firstImage.close();
              secondImage.close();
              mergedImage.close();
              IJ.showStatus("Plugin aborted.");
              return;
            }
            slice = firstSlice + 4 * (z - 1);
            compositeMergedImage.setPosition(ch, z, t);
            
            // Add data from the first image.
            ipSlice = firstStack.getProcessor(slice);
            merged.insert(ipSlice, 0, 0);
            
            // Now add data from the second image.
            ipSlice = secondStack.getProcessor(slice);
            if (mergeFluorescence) {                                    // Add second image fluorescence data.
              if (ch != 4) {
                merged.copyBits(ipSlice, 0, 0, Blitter.ADD);
              }
            }
            else {                                                      // Copy second image below first.
              merged.copyBits(ipSlice, 0, height + GAP, Blitter.COPY);
            }
          }
        }
      }
      
      if (channels == 4) {                                              // Set custom blue color for 3-color movies.
        Color myBlue = new Color(0, 96, 255);
        LUT blueLUT = LUT.createLutFromColor(myBlue);
        compositeMergedImage.setChannelLut(blueLUT, 3);
    }
      
      firstImage.setPosition(4,1,1);
      grayMax = firstImage.getDisplayRangeMax();
      grayMin = firstImage.getDisplayRangeMin();
      firstImage.setPosition(3,1,1);
      blueMax = firstImage.getDisplayRangeMax();
      firstImage.setPosition(2,1,1);
      greenMax = firstImage.getDisplayRangeMax();
      firstImage.setPosition(1,1,1);
      redMax = firstImage.getDisplayRangeMax();
      
      firstImage.close();
      secondImage.close();
      
      compositeMergedImage.setPosition(4,1,1);
      compositeMergedImage.setDisplayRange(grayMin, grayMax);
      compositeMergedImage.setPosition(3,1,1);
      compositeMergedImage.setDisplayRange(0, blueMax);
      compositeMergedImage.setPosition(2,1,1);
      compositeMergedImage.setDisplayRange(0, greenMax);
      compositeMergedImage.setPosition(1,1,1);
      compositeMergedImage.setDisplayRange(0, redMax);
      
      String hyperStackInfo = "fluorescence channels: " + channels;
      compositeMergedImage.setProperty("Info", hyperStackInfo);
      
      compositeMergedImage.changes = true;
      compositeMergedImage.show();
	}

}

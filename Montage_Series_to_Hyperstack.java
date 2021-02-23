package IJ_Plugins;

import java.awt.Color;

import ij.*;
import ij.plugin.PlugIn;
import ij.process.LUT;
import ij.gui.*;
import ij.gui.GenericDialog;

/* Converts an edited 4-channel montage series to a hyperstack that can be used to make and analyze movies. */
public class Montage_Series_to_Hyperstack implements PlugIn {
  
	private ImagePlus originalImage;
	private int width, height, slices, frames, columns, channels, GAP;       // Hyperstack parameters.
	private int first, last;                                                 // Frames to be exported.
	private double redMax, greenMax, blueMax, grayMax, grayMin;
	private String title, info;
	private String[] parameters = new String[6];                             // Montage parameters.
	
	//------------------------------------------------------------------------------------------------------------------------  
	
    public void run(String arg) {
      
      IJ.resetEscape();
      
      originalImage = IJ.getImage();
      title = originalImage.getTitle();
      
      if ( !(originalImage.getBitDepth() == 8)  || !(title.endsWith(".tif") || title.endsWith(".TIF")) || 
          !originalImage.isHyperStack() || !(originalImage.getNSlices() == 1) ) {
        IJ.showMessage("This plugin requires an 8-bit TIFF montage series.");
        originalImage.close();
        return;
      }
      
      title = title.substring(0, title.length() - 4);               // Remove the extension.
      frames = originalImage.getNFrames();
      
      GenericDialog gd = new GenericDialog("Time Points");
      gd.addNumericField("First time point:", 1, 0);                // First time point in the exported hyperstack.
      gd.addNumericField("Last time point:", frames, 0);            // Last time point in the exported hyperstack.
      gd.showDialog();
      if (gd.wasCanceled()) return;
      
      first = (int) gd.getNextNumber();
      last = (int) gd.getNextNumber();
      frames = last - first + 1;                                    // The actual number of frames to be exported.
      
      if (!(first < last)) {
        IJ.showMessage("Those time points are not valid.");
        return;
      }
      
      // Get the montage parameters from the Info property, and convert from Strings to integers.
      info = (String) originalImage.getProperty("Info");
      parameters = info.split("\n");
      slices = extractParameter(parameters[0]);
      columns = extractParameter(parameters[1]);
      channels = extractParameter(parameters[2]);                   // Number of channels in the original movie.
      GAP = extractParameter(parameters[3]);
      width = extractParameter(parameters[4]);
      height = extractParameter(parameters[5]);
      
      // Create a 4D hyperstack with 4 channels (red, green, blue, gray).
      int images = 4 * slices * frames;
      ImagePlus backToStack = IJ.createImage(title + " 4D.tif", "8-bit black", width, height, images);
      backToStack.setDimensions(4, slices, frames);
      CompositeImage hyperStack = new CompositeImage(backToStack, CompositeImage.COMPOSITE);
      hyperStack.setOpenAsHyperStack(true);
      backToStack.close();
      
      originalImage.killRoi();
      
      // Copy the slices from the montage to the hyperstack.
      int xPos, yPos;
      for (int t = first; t <= last; t++) {
        for (int ch = 1; ch <= 4; ch++) {
          if (IJ.escapePressed()) {
            originalImage.close();
            hyperStack.close();
            IJ.showStatus("Plugin aborted.");
            return;
          }
          originalImage.setPositionWithoutUpdate(ch, 1, t);
          for (int z = 1; z <= slices; z++) {
            xPos = GAP + ((z - 1) % columns) * (width + GAP);
            yPos = GAP + ((z - 1) / columns) * (height + GAP);
            Roi roi = new Roi(xPos, yPos, width, height);
            originalImage.setRoi(roi, false);
            originalImage.copy(false);
            hyperStack.setPosition(ch, z, t - first + 1);
            hyperStack.paste();
          }
          IJ.showProgress(t - first + 1, last - first + 1);
        }
      }
      
      originalImage.killRoi();
      
      if (channels == 4) {                                              // Set custom blue color for 3-color movies.
        Color myBlue = new Color(0, 96, 255);
        LUT blueLUT = LUT.createLutFromColor(myBlue);
        hyperStack.setChannelLut(blueLUT, 3);
    }
      
      originalImage.setPosition(4,1,1);
      grayMax = originalImage.getDisplayRangeMax();
      grayMin = originalImage.getDisplayRangeMin();
      originalImage.setPosition(3,1,1);
      blueMax = originalImage.getDisplayRangeMax();
      originalImage.setPosition(2,1,1);
      greenMax = originalImage.getDisplayRangeMax();
      originalImage.setPosition(1,1,1);
      redMax = originalImage.getDisplayRangeMax();
      originalImage.updateAndDraw();
      
      hyperStack.setPosition(4,1,1);
      hyperStack.setDisplayRange(grayMin, grayMax);
      hyperStack.setPosition(3,1,1);
      hyperStack.setDisplayRange(0, blueMax);
      hyperStack.setPosition(2,1,1);
      hyperStack.setDisplayRange(0, greenMax);
      hyperStack.setPosition(1,1,1);
      hyperStack.setDisplayRange(0, redMax);
      
      String hyperStackInfo = "fluorescence channels: " + channels;
      hyperStack.setProperty("Info", hyperStackInfo);
      
      hyperStack.changes = true;
      hyperStack.show();
 
    }

    //========================================================================================================================
    
    /* Extracts an integer parameter from a parameter string in the Info property. */
    private int extractParameter(String parameterString) {
      int start = parameterString.indexOf(":") + 2;
      return Integer.parseInt(parameterString.substring(start));
    }
    
}

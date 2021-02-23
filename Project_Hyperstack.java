package IJ_Plugins;

import ij.*;
import ij.plugin.*;

/* Converts a 4-channel 4D hyperstack to 16-bit and does an average projection. */
public class Project_Hyperstack implements PlugIn {
  
	private ImagePlus hyperStack, projection;
	private double grayMax, grayMin;                                         // Gray channel range set during processing.
	private String title;
	
	//------------------------------------------------------------------------------------------------------------------------  
	
    public void run(String arg) {

      hyperStack = IJ.getImage();
      title = hyperStack.getTitle();                                      // Keep the extension.
 
      if ( !(hyperStack.getBitDepth() == 8)  || !(title.endsWith(".tif") || title.endsWith(".TIF")) || 
          !hyperStack.isHyperStack() || !(hyperStack.getNSlices() > 1) ) {
        IJ.showMessage("This plugin requires an 8-bit TIFF hyperstack.");
        hyperStack.close();
        return;
      }
      
      hyperStack.setPosition(4,1,1);
      grayMax = hyperStack.getDisplayRangeMax();
      grayMin = hyperStack.getDisplayRangeMin();
      
      // Split the image into channels, convert each channel to 16-bit, and then merge back into a hyperstack.
      IJ.run("Split Channels");
      String C1_Window = "C1-" + title;
      String C2_Window = "C2-" + title;
      String C3_Window = "C3-" + title;
      String C4_Window = "C4-" + title;
      
      IJ.selectWindow(C1_Window);
      IJ.run("16-bit");
      IJ.selectWindow(C2_Window);
      IJ.run("16-bit");
      IJ.selectWindow(C3_Window);
      IJ.run("16-bit");
      IJ.selectWindow(C4_Window);
      IJ.run("16-bit");
      
      String mergeChannelsArgument = "c1=[" + C1_Window + "] c2=[" + C2_Window + "] c3=[" + C3_Window + "] c4=[" + C4_Window + "] create";
      IJ.run("Merge Channels...", mergeChannelsArgument);
      
      // Multiply the 16-bit merged hyperstack by 256, then do a Z projection and close the merged hyperstack.
      IJ.run("Multiply...", "value=256");
      IJ.run("Z Project...");
      IJ.selectWindow(title);
      IJ.run("Close");
      projection = IJ.getImage();
      
      // Set the display ranges for the four channels, preserving the original custom gray range.
      projection.setPosition(4,1,1);
      projection.setDisplayRange(grayMin * 256, grayMax * 256);
      projection.updateAndDraw();
      projection.setPosition(3,1,1);
      IJ.run("Enhance Contrast", "saturated=0.1");
      projection.updateAndDraw();
      projection.setPosition(2,1,1);
      IJ.run("Enhance Contrast", "saturated=0.1");
      projection.updateAndDraw();
      projection.setPosition(1,1,1);
      IJ.run("Enhance Contrast", "saturated=0.1");
      projection.updateAndDraw();
      
	}

}

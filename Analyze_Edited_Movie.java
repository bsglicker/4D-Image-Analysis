package IJ_Plugins;

import ij.*;
import ij.plugin.*;
import ij.process.*;
import ij.measure.ResultsTable;
import ij.gui.GenericDialog;

/* Measures individual and integrated fluorescence values for an edited 4-channel 4D hyperstack. */
public class Analyze_Edited_Movie implements PlugIn {
  
	private ImagePlus hyperStack;
	private int frames, slices, channels, width, height;                  // Hyperstack parameters.
	private double interval;
	private String title, info;
	private double[] redValues, greenValues, blueValues;
	private double[] redValuesIntegrated, greenValuesIntegrated, blueValuesIntegrated;
	private ResultsTable results = new ResultsTable();
	private ImagePlus red, green, blue;
	
	//------------------------------------------------------------------------------------------------------------------------  
	
    public void run(String arg) {

      hyperStack = IJ.getImage();
      title = hyperStack.getTitle();
 
      if ( !(hyperStack.getBitDepth() == 8)  || !(title.endsWith(".tif") || title.endsWith(".TIF")) || 
          !hyperStack.isHyperStack() || !(hyperStack.getNSlices() > 1) ) {
        IJ.showMessage("This plugin requires an 8-bit TIFF hyperstack.");
        hyperStack.close();
        return;
      }
      
      title = title.substring(0, title.length() - 4);                   // Remove the extension.
     
      GenericDialog gd = new GenericDialog("Z-Stack Interval");
      gd.addNumericField("Z-Stack Interval:", 2.00, 2);                 // The default interval is 2.00 sec.
      gd.showDialog();
      if (gd.wasCanceled()) return;
      interval = gd.getNextNumber();
      
      width = hyperStack.getWidth();
      height = hyperStack.getHeight();
      slices = hyperStack.getNSlices();
      frames = hyperStack.getNFrames();
      
      info = (String) hyperStack.getProperty("Info");
      int start = info.indexOf(":") + 2;
      channels = Integer.parseInt(info.substring(start));               // Number of channels in the original movie.
      
      redValues = new double[frames];
      greenValues = new double[frames];
      blueValues = new double[frames];
      redValuesIntegrated = new double[frames];
      greenValuesIntegrated = new double[frames];
      blueValuesIntegrated = new double[frames];
      
      // Create images to hold the fluorescence data.
      red = IJ.createImage("Red", "8-bit black", width, height, 1);
      green = IJ.createImage("Green", "8-bit black", width, height, 1);
      blue = IJ.createImage("Blue", "8-bit black", width, height, 1);
      
      // For each time point, sum the mean values for the fluorescence images in each slice.
      for (int t = 1; t <= frames; t++) {
        for (int z = 1; z <= slices; z++) {
          hyperStack.setPositionWithoutUpdate(1, z, t);
          red.getProcessor().copyBits(hyperStack.getProcessor(), 0, 0, Blitter.COPY);
          redValues[t - 1] += red.getStatistics().mean;
          hyperStack.setPositionWithoutUpdate(2, z, t); 
          green.getProcessor().copyBits(hyperStack.getProcessor(), 0, 0, Blitter.COPY);
          greenValues[t - 1] += green.getStatistics().mean;
          hyperStack.setPositionWithoutUpdate(3, z, t);
          blue.getProcessor().copyBits(hyperStack.getProcessor(), 0, 0, Blitter.COPY);
          blueValues[t - 1] += blue.getStatistics().mean;
        }
        if (t == 1) {
          redValuesIntegrated[0] = redValues[0];
          greenValuesIntegrated[0] = greenValues[0];
          blueValuesIntegrated[0] = blueValues[0];
        }
        else {
          redValuesIntegrated[t - 1] = redValuesIntegrated[t - 2] + redValues[t - 1];
          greenValuesIntegrated[t - 1] = greenValuesIntegrated[t - 2] + greenValues[t - 1];
          blueValuesIntegrated[t - 1] = blueValuesIntegrated[t - 2] + blueValues[t - 1];
        }
        
        results.incrementCounter();
        results.addValue("Time", interval * (t - 1));
        if (channels >= 3) {
          results.addValue("Red", redValues[t - 1]);
        }
        results.addValue("Green", greenValues[t - 1]);
        if (channels == 4) {
          results.addValue("Blue",  blueValues[t - 1]);
        }
        if (channels >= 3) {
          results.addValue("Red Integrated", redValuesIntegrated[t - 1]);
        }
        results.addValue("Green Integrated", greenValuesIntegrated[t - 1]);
        if (channels == 4) {
          results.addValue("Blue Integrated",  blueValuesIntegrated[t - 1]);
        }
      }
      
      red.close();
      green.close();
      blue.close();
      
      //results.showRowNumbers(false);
      results.show(title);
      
      hyperStack.setPosition(1,1,1);
	}

}

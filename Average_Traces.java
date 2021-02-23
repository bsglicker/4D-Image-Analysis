package IJ_Plugins;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;

import ij.*;
import ij.plugin.*;
import ij.measure.ResultsTable;
import ij.io.OpenDialog;

/* Averages integrated fluorescence values for a set of analyzed events. */
public class Average_Traces implements PlugIn {
  
  private int traces;                                                       // Number of trace files to be averaged.
  private double maxValue;                                                  // Variable for normalizing plots.
  private int columns;                                                      // Number of columns in each imported trace file.
  private ResultsTable[] traceValues;                                       // Array of imported trace file data.
  private int[] size;                                                       // Array of trace lengths for the input data.
  private double time, deltaT;                                              // Time values, and interval between time points.
  private boolean red = false, blue = false;                                // Are red and blue channels present?
  private int[] greenPeak, redPeak, bluePeak;                               // Peak time points for the smoothed traces.
  private int greenSize, redSize, blueSize, fullSize;                       // Lengths of the averaged traces and the final table.
  private int leftGreen, leftRed, leftBlue;                                 // Number of time points to the left of each averaged trace.
  private int rightGreen, rightRed, rightBlue;                              // Number of time points to the right of each averaged trace.
  private double[] greenTraceMean, redTraceMean, blueTraceMean;             // Heights of the averaged traces.
  private double[] greenTraceSEM, redTraceSEM, blueTraceSEM;                // SEM values of averaged traces.
  private int greenToRed, greenToBlue, redToBlue;                           // Offset time points between the averaged traces.
  private double[][] greenEndpoints, redEndpoints, blueEndpoints;           // Start and end time points for the averaged traces.
  private double greenMean, redMean, blueMean,
                 greenSEM, redSEM, blueSEM,
                 greenRedStartMean, greenRedEndMean,
                 greenRedStartSEM, greenRedEndSEM,
                 greenBlueStartMean, greenBlueEndMean,
                 greenBlueStartSEM, greenBlueEndSEM,
                 redBlueStartMean, redBlueEndMean,
                 redBlueStartSEM, redBlueEndSEM;                            // Offset values for the start and end times of the traces.
	//------------------------------------------------------------------------------------------------------------------------  
	
    public void run(String arg) {
      
      IJ.showMessage("Please choose a CSV file in the folder of analyzed trace files.");
      String directory = new OpenDialog("Choose First Data File", "").getDirectory();
      File[] filesInFolder = new File(directory).listFiles(new FilenameFilter() {
        
        @Override
        public boolean accept(File dir, String name) {
          if(name.toLowerCase().endsWith(".csv")){
               return true;
          } else {
               return false;
          }
        }
      });
      Arrays.sort(filesInFolder);

      traces = filesInFolder.length;
      if (traces == 0 ) {
        IJ.showMessage("This plugin requires CSV files.");
        return;
      }
      else if (traces == 1) {
        IJ.showMessage("This plugin requires more than one CSV file.");
        return;
      }
      
      traceValues = new ResultsTable[traces];
      size = new int[traces];
      
      // Import the trace data files into the traceValues ResultsTable array.
      for (int i = 0; i < traces; i++) {
        traceValues[i] = ResultsTable.open2(filesInFolder[i].getPath());
        size[i] = traceValues[i].size();
        traceValues[i].setPrecision(6);
      }
      
      // Determine the interval between data points. This interval is assumed to be the same for all traces.
      deltaT = traceValues[0].getValue("Time",  1) - traceValues[0].getValue("Time",  0);
      
      // Determine which channels are present by examining the number of columns.
      columns = traceValues[0].getHeadings().length;
      if (columns >= 5) {
        red = true;
      }
      if (columns == 7) {
        blue = true;
      }
      
      ResultsTable averagedTable = new ResultsTable();
      averagedTable.setPrecision(6);
      
      //----------------------------------------------------------------------------------------------------------------------
      
      // START WITH GREEN, WHICH IS ALWAYS PRESENT.
      
      greenPeak = new int[traces];                                           // Peak time points for the smoothed traces.
      greenEndpoints = new double[traces][2];                                // Start and end time points for the smoothed traces.
      
      // Make smoothed and normalized traces, and add a column to each imported ResultsTable, and analyze the traces.
      for (int i = 0; i < traces; i++) {
        double[] integral = new double[size[i]];
        for (int j = 0; j < size[i]; j++) {
          integral[j] = traceValues[i].getValue("Green Integrated", j);
        }

        double[] smoothedTrace = new double[size[i]];
        smoothedTrace = differentiate(integral, 1.0);
        
        // Normalize the raw and smoothed traces, and add the smoothed trace to the appropriate ResultsTable in a new column.
        maxValue = 0.0;
        for (int j = 0; j < size[i]; j++) {
          if (smoothedTrace[j] > maxValue) {
            maxValue = smoothedTrace[j];
          }
        }
        for (int j = 0; j < size[i]; j++) {
          smoothedTrace[j] /= maxValue;
          traceValues[i].setValue("Green Smoothed", j, smoothedTrace[j]);
          traceValues[i].setValue("Green", j, traceValues[i].getValue("Green", j) / maxValue);
        }
 
        // Define the peak as the 50% point of the integral.
        greenPeak[i] = integralTimePoint(integral, 50.0);
        
        // Find the start and end time points for the smoothed trace.
        greenEndpoints[i] = findEndpoints(smoothedTrace, greenPeak[i]);
      }
      
      // Align the smoothed and normalized traces.
      leftGreen = 0;                                                      // Number of time points left of the averaged peak.
      rightGreen = 0;                                                     // Number of time points right of the averaged peak.
      for (int i = 0; i < traces; i++) {
        if (greenPeak[i] > leftGreen) {
          leftGreen = greenPeak[i];
        }
        if (size[i] - 1 - greenPeak[i] > rightGreen) {
          rightGreen = size[i] - 1 - greenPeak[i];
        }
      }
      
      // Create a set of smoothed trace arrays of equal size, with the traces aligned around their peak values.
      greenSize = leftGreen + rightGreen + 1;
      double[][] greenRawArray = new double[traces][greenSize];
      double[][] greenSmoothArray = new double[traces][greenSize];
      for (int i = 0; i < traces; i++) {
        int skip = leftGreen - greenPeak[i];                                 // Number of time points to skip on the left side.
        for (int j = 0; j < greenSize; j++) {
          if (j < skip || j >= skip + size[i]) {
            greenRawArray[i][j] = 0.0;
            greenSmoothArray[i][j] = 0.0;
          }
          else {
            greenRawArray[i][j] = traceValues[i].getValue("Green", j - skip);
            greenSmoothArray[i][j] = traceValues[i].getValue("Green Smoothed", j - skip);
          }
        }
      }

      // Calculate mean and SEM values for the smoothed traces.
      greenTraceMean = new double[greenSize];
      greenTraceSEM = new double[greenSize];
      for (int j = 0; j < greenSize; j++) {
        greenTraceMean[j] = 0.0;
        for (int i = 0; i < traces; i++) {
          greenTraceMean[j] += greenSmoothArray[i][j];
        }
        greenTraceMean[j] /= traces;                                            // Normalize to the number of traces.
      }
      double greenMax = findMax(greenTraceMean);
      for (int j = 0; j < greenSize; j++) {
        greenTraceSEM[j] = 0.0;
        double diff;
        for (int i = 0; i < traces; i++) {
          diff = greenSmoothArray[i][j] - greenTraceMean[j];
          greenTraceSEM[j] += diff * diff;
        }
        greenTraceSEM[j] = Math.sqrt(greenTraceSEM[j] / ( (traces - 1) * traces));
        
        greenTraceMean[j] /= greenMax;
        greenTraceSEM[j] /= greenMax;
      }
      
      // Show the processed data in a ResultsTable.
      ResultsTable greenTracesTable = new ResultsTable();
      greenTracesTable.setPrecision(6);
      time = 0.0;
      for (int j = 0; j < greenSize; j++) {
        greenTracesTable.incrementCounter();
        greenTracesTable.addValue("Time",  time);
        time += deltaT;
        for (int i = 0; i < traces; i++) {
          greenTracesTable.addValue("G raw " + Integer.toString(i), greenRawArray[i][j]);
        }
        for (int i = 0; i < traces; i++) {
          greenTracesTable.addValue("G smooth " + Integer.toString(i), greenSmoothArray[i][j]);
        }
      }
      greenTracesTable.show("Green Traces");
      
      //----------------------------------------------------------------------------------------------------------------------
      
      // ANALYZE RED IF PRESENT.
      
      if (red) {
        
        redPeak = new int[traces];                                                // Peak time points for the smoothed traces.
        redEndpoints = new double[traces][2];                                     // Start and end time points for the smoothed traces.
        
        // Make smoothed and normalized traces, and add a column to each imported ResultsTable, and analyze the traces.
        for (int i = 0; i < traces; i++) {
          double[] integral = new double[size[i]];
          for (int j = 0; j < size[i]; j++) {
            integral[j] = traceValues[i].getValue("Red Integrated", j);
          }
          
          double[] smoothedTrace = new double[size[i]];
          smoothedTrace = differentiate(integral, 1.0);
          
          // Normalize the raw and smoothed traces, and add the smoothed trace to the appropriate ResultsTable in a new column.
          maxValue = 0.0;
          for (int j = 0; j < size[i]; j++) {
            if (smoothedTrace[j] > maxValue) {
              maxValue = smoothedTrace[j];
            }
          }
          for (int j = 0; j < size[i]; j++) {
            smoothedTrace[j] /= maxValue;
            traceValues[i].setValue("Red Smoothed", j, smoothedTrace[j]);
            traceValues[i].setValue("Red", j, traceValues[i].getValue("Red", j) / maxValue);
          }
   
          // Define the peak as the 50% point of the integral.
          redPeak[i] = integralTimePoint(integral, 50.0);
          
          // Find the start and end time points for the smoothed trace.
          redEndpoints[i] = findEndpoints(smoothedTrace, redPeak[i]);
        }
        
        // Align the smoothed and normalized traces.
        leftRed = 0;                                                        // Number of time points left of the averaged peak.
        rightRed = 0;                                                       // Number of time points right of the averaged peak.
        for (int i = 0; i < traces; i++) {
          if (redPeak[i] > leftRed) {
            leftRed = redPeak[i];
          }
          if (size[i] - 1 - redPeak[i] > rightRed) {
            rightRed = size[i] - 1 - redPeak[i];
          }
        }
        
        // Create a set of smoothed trace arrays of equal size, with the traces aligned around their peak values.
        redSize = leftRed + rightRed + 1;
        double[][] redRawArray = new double[traces][redSize];
        double[][] redSmoothArray = new double[traces][redSize];
        for (int i = 0; i < traces; i++) {
          int skip = leftRed - redPeak[i];                                     // Number of time points to skip on the left side.
          for (int j = 0; j < redSize; j++) {
            if (j < skip || j >= skip + size[i]) {
              redRawArray[i][j] = 0.0;
              redSmoothArray[i][j] = 0.0;
            }
            else {
              redRawArray[i][j] = traceValues[i].getValue("Red", j - skip);
              redSmoothArray[i][j] = traceValues[i].getValue("Red Smoothed", j - skip);
            }
          }
        }
        
        // Calculate mean and SEM values for the smoothed traces.
        redTraceMean = new double[redSize];
        redTraceSEM = new double[redSize];
        for (int j = 0; j < redSize; j++) {
          redTraceMean[j] = 0.0;
          for (int i = 0; i < traces; i++) {
            redTraceMean[j] += redSmoothArray[i][j];
          }
          redTraceMean[j] /= traces;                                            // Normalize to the number of traces.
        }
        double redMax = findMax(redTraceMean);
        for (int j = 0; j < redSize; j++) {
          redTraceSEM[j] = 0.0;
          double diff;
          for (int i = 0; i < traces; i++) {
            diff = redSmoothArray[i][j] - redTraceMean[j];
            redTraceSEM[j] += diff * diff;
          }
          redTraceSEM[j] = Math.sqrt(redTraceSEM[j] / ( (traces - 1) * traces));
          
          redTraceMean[j] /= redMax;
          redTraceSEM[j] /= redMax;
        }
        
        // Show the processed data in a ResultsTable.
        ResultsTable redTracesTable = new ResultsTable();
        redTracesTable.setPrecision(6);
        time = 0.0;
        for (int j = 0; j < redSize; j++) {
          redTracesTable.incrementCounter();
          redTracesTable.addValue("Time",  time);
          time += deltaT;
          for (int i = 0; i < traces; i++) {
            redTracesTable.addValue("R raw " + Integer.toString(i), redRawArray[i][j]);
          }
          for (int i = 0; i < traces; i++) {
            redTracesTable.addValue("R smooth " + Integer.toString(i), redSmoothArray[i][j]);
          }
        }
        redTracesTable.show("Red Traces");
    
      } 
      
      //----------------------------------------------------------------------------------------------------------------------
      
      // ANALYZE BLUE IF PRESENT.
      
      if (blue) {
        
        bluePeak = new int[traces];                                               // Peak time points for the smoothed traces.
        blueEndpoints = new double[traces][2];                                    // Start and end time points for the smoothed traces.
        
        // Make smoothed and normalized traces, and add a column to each imported ResultsTable, and analyze the traces.
        for (int i = 0; i < traces; i++) {
          double[] integral = new double[size[i]];
          for (int j = 0; j < size[i]; j++) {
            integral[j] = traceValues[i].getValue("Blue Integrated", j);
          }
          
          double[] smoothedTrace = new double[size[i]];
          smoothedTrace = differentiate(integral, 1.0);
          
          // Normalize the raw and smoothed traces, and add the smoothed trace to the appropriate ResultsTable in a new column.
          maxValue = 0.0;
          for (int j = 0; j < size[i]; j++) {
            if (smoothedTrace[j] > maxValue) {
              maxValue = smoothedTrace[j];
            }
          }
          for (int j = 0; j < size[i]; j++) {
            smoothedTrace[j] /= maxValue;
            traceValues[i].setValue("Blue Smoothed", j, smoothedTrace[j]);
            traceValues[i].setValue("Blue", j, traceValues[i].getValue("Blue", j) / maxValue);
          }
   
          // Define the peak as the 50% point of the integral.
          bluePeak[i] = integralTimePoint(integral, 50.0);

          // Find the start and end time points for the smoothed trace.
          blueEndpoints[i] = findEndpoints(smoothedTrace, bluePeak[i]);
        }
        
        // Align the smoothed and normalized traces.
        leftBlue = 0;                                                       // Number of time points left of the averaged peak.
        rightBlue = 0;                                                      // Number of time points right of the averaged peak.
        for (int i = 0; i < traces; i++) {
          if (bluePeak[i] > leftBlue) {
            leftBlue = bluePeak[i];
          }
          if (size[i] - 1 - bluePeak[i] > rightBlue) {
            rightBlue = size[i] - 1 - bluePeak[i];
          }
        }
        
        // Create a set of smoothed trace arrays of equal size, with the traces aligned around their peak values.
        blueSize = leftBlue + rightBlue + 1;
        double[][] blueRawArray = new double[traces][blueSize];
        double[][] blueSmoothArray = new double[traces][blueSize];
        for (int i = 0; i < traces; i++) {
          int skip = leftBlue - bluePeak[i];                                   // Number of time points to skip on the left side.
          for (int j = 0; j < blueSize; j++) {
            if (j < skip || j >= skip + size[i]) {
              blueRawArray[i][j] = 0.0;
              blueSmoothArray[i][j] = 0.0;
            }
            else {
              blueRawArray[i][j] = traceValues[i].getValue("Blue", j - skip);
              blueSmoothArray[i][j] = traceValues[i].getValue("Blue Smoothed", j - skip);
            }
          }
        }
        
        // Calculate mean and SEM values for the smoothed traces.
        blueTraceMean = new double[blueSize];
        blueTraceSEM = new double[blueSize];
        for (int j = 0; j < blueSize; j++) {
          blueTraceMean[j] = 0.0;
          for (int i = 0; i < traces; i++) {
            blueTraceMean[j] += blueSmoothArray[i][j];
          }
          blueTraceMean[j] /= traces;                                             // Normalize to the number of traces.
        }
        double blueMax = findMax(blueTraceMean);
        for (int j = 0; j < blueSize; j++) {
          blueTraceSEM[j] = 0.0;
          double diff;
          for (int i = 0; i < traces; i++) {
            diff = blueSmoothArray[i][j] - blueTraceMean[j];
            blueTraceSEM[j] += diff * diff;
          }
          blueTraceSEM[j] = Math.sqrt(blueTraceSEM[j] / ( (traces - 1) * traces));
          
          blueTraceMean[j] /= blueMax;
          blueTraceSEM[j] /= blueMax;
        }
        
        // Show the processed data in a ResultsTable.
        ResultsTable blueTracesTable = new ResultsTable();
        blueTracesTable.setPrecision(6);
        time = 0.0;
        for (int j = 0; j < blueSize; j++) {
          blueTracesTable.incrementCounter();
          blueTracesTable.addValue("Time",  time);
          time += deltaT;
          for (int i = 0; i < traces; i++) {
            blueTracesTable.addValue("B raw " + Integer.toString(i), blueRawArray[i][j]);
          }
          for (int i = 0; i < traces; i++) {
            blueTracesTable.addValue("B smooth " + Integer.toString(i), blueSmoothArray[i][j]);
          }
        }
        blueTracesTable.show("Blue Traces");
        
      }
      
      //----------------------------------------------------------------------------------------------------------------------
      
      // ASSEMBLE THE FINAL OUTPUT DATA.
      
      int greenOffset = 0, redOffset = 0, blueOffset = 0;                         // Offset time point values for the final table.
      
      fullSize = greenSize;                                                       // Initial guess for the size of the final table.
      
      // Calculate the averaged offsets between the averaged traces in the different channels.
      if (red) {
        greenToRed = 0;
        for (int i = 0; i < traces; i++) {
          greenToRed += redPeak[i] - greenPeak[i];
        }
        double gTR = (double) greenToRed / (double) traces;
        greenToRed = (int) Math.round(gTR) + leftGreen - leftRed;                 // Compensate for shifts during trace averaging.
      }
      
      if (blue) {
        greenToBlue = 0;
        redToBlue = 0;
        for (int i = 0; i < traces; i++) {
          greenToBlue += bluePeak[i] - greenPeak[i];
          redToBlue += bluePeak[i] - redPeak[i];
        }
        double gTB = (double) greenToBlue / (double) traces;
        greenToBlue = (int) Math.round(gTB) + leftGreen - leftBlue;               // Compensate for shifts during trace averaging.
        double rTB = (double) redToBlue / (double) traces;
        redToBlue = (int) Math.round(rTB) + leftRed - leftBlue;                   // Compensate for shifts during trace averaging.
      }
        
      // Calculate the size of the final ResultsTable and the offset time point values for populating it.
      if (red) {
        if (greenToRed >= 0) {
          fullSize = Math.max(greenSize, redSize + greenToRed);
          redOffset += greenToRed;
        }
        else {
          fullSize = Math.max(redSize, greenSize - greenToRed);
          greenOffset -= greenToRed;
        }
      }
      
      if (blue) {
        if (greenToRed >= 0) {
          if (greenToBlue >= 0) {
            fullSize = Math.max(fullSize,  blueSize + greenToBlue);
            blueOffset += greenToBlue;
          }
          else {
            fullSize = Math.max(blueSize,  fullSize - greenToBlue);
            greenOffset -= greenToBlue;
            redOffset -= greenToBlue;
          }
        }
        else {                                                                    // greenToRed < 0
          if (redToBlue >= 0) {
            fullSize = Math.max(fullSize,  blueSize + redToBlue);
            blueOffset += redToBlue;
          }
          else {
            fullSize = Math.max(blueSize,  fullSize - redToBlue);
            greenOffset -= redToBlue;
            redOffset -= redToBlue;
          }
        }
      }
      
      // Copy the data into a final ResultsTable of length fullSize.
      ResultsTable finalTracesTable = new ResultsTable();
      finalTracesTable.setPrecision(6);
      time = 0.0;
      
      // Initially populate the table with zero values.
      for (int j = 0; j < fullSize; j++) {
        finalTracesTable.incrementCounter();
        finalTracesTable.addValue("Time",  time);
        time += deltaT;
        finalTracesTable.addValue("Green",  0.0);
        finalTracesTable.addValue("Green 95% CI",  0.0);
        if (red) {
          finalTracesTable.addValue("Red",  0.0);
          finalTracesTable.addValue("Red 95% CI",  0.0);
        }
        if (blue) {
          finalTracesTable.addValue("Blue",  0.0);
          finalTracesTable.addValue("Blue 95% CI",  0.0);
        }
      }
      
      // Replace the appropriate table cells with averaged trace values.
      for (int j = 0; j < greenSize; j++) {
        finalTracesTable.setValue("Green", j + greenOffset, greenTraceMean[j]);
        finalTracesTable.setValue("Green 95% CI", j + greenOffset, 1.96 * greenTraceSEM[j]);
      }
      if (red) {
        for (int j = 0; j < redSize; j++) {
          finalTracesTable.setValue("Red", j + redOffset, redTraceMean[j]);
          finalTracesTable.setValue("Red 95% CI", j + redOffset, 1.96 * redTraceSEM[j]);
        }
      }
      if (blue) {
        for (int j = 0; j < blueSize; j++) {
          finalTracesTable.setValue("Blue", j + blueOffset, blueTraceMean[j]);
          finalTracesTable.setValue("Blue 95% CI", j + blueOffset, 1.96 * blueTraceSEM[j]);
        }
      }
      
      finalTracesTable.show("Averaged Traces");
      
      
      // DETERMINE THE AVERAGE OFFSETS BETWEEN ARRIVAL AND DEPARTURE TIMES FOR THE MARKERS.
      
      // Create a ResultsTable for the offset values.
      ResultsTable offsetValues = new ResultsTable();
      offsetValues.setPrecision(1);
      
      double diff, diffStart, diffEnd;
      
      // Calculate the average trace duration and SEM value.
      greenMean = 0.0;
      greenSEM = 0.0;
      for (int i = 0; i < traces; i++ ) {
        greenMean += (greenEndpoints[i][1] - greenEndpoints[i][0]) * deltaT;
      }
      greenMean /= traces;
      
      for (int i = 0; i < traces; i++) {
        diff = (greenEndpoints[i][1] - greenEndpoints[i][0]) * deltaT - greenMean;
        greenSEM += diff * diff;
      }
      greenSEM = Math.sqrt(greenSEM / ( (traces - 1) * traces));
      
      if (red) {
        // Calculate the average trace duration and SEM value.
        redMean = 0.0;
        redSEM = 0.0;
        for (int i = 0; i < traces; i++ ) {
          redMean += (redEndpoints[i][1] - redEndpoints[i][0]) * deltaT;
        }
        redMean /= traces;
        
        for (int i = 0; i < traces; i++) {
          diff = (redEndpoints[i][1] - redEndpoints[i][0]) * deltaT - redMean;
          redSEM += diff * diff;
        }
        redSEM = Math.sqrt(redSEM / ( (traces - 1) * traces));
        
        greenRedStartMean = 0.0;
        greenRedEndMean = 0.0;
        greenRedStartSEM = 0.0;
        greenRedEndSEM = 0.0; 
        
        // Calculate the green-to-red average offsets.
        for (int i = 0; i < traces; i++) {
          greenRedStartMean += (redEndpoints[i][0] - greenEndpoints[i][0]) * deltaT;
          greenRedEndMean += (redEndpoints[i][1] - greenEndpoints[i][1]) * deltaT;
        }
        greenRedStartMean /= traces;
        greenRedEndMean /= traces;
        
        // Calculate the green-to-red SEM values.
        for (int i = 0; i < traces; i++) {
          diffStart = (redEndpoints[i][0] - greenEndpoints[i][0]) * deltaT - greenRedStartMean;
          greenRedStartSEM += diffStart * diffStart;
          diffEnd = (redEndpoints[i][1] - greenEndpoints[i][1]) * deltaT - greenRedEndMean;
          greenRedEndSEM += diffEnd * diffEnd;
        }
        greenRedStartSEM = Math.sqrt(greenRedStartSEM / ( (traces - 1) * traces));
        greenRedEndSEM = Math.sqrt(greenRedEndSEM / ( (traces - 1) * traces));  
      }
      
      if (blue) {
        // Calculate the average trace duration and SEM value.
        blueMean = 0.0;
        blueSEM = 0.0;
        for (int i = 0; i < traces; i++ ) {
          blueMean += (blueEndpoints[i][1] - blueEndpoints[i][0]) * deltaT;
        }
        blueMean /= traces;
        
        for (int i = 0; i < traces; i++) {
          diff = (blueEndpoints[i][1] - blueEndpoints[i][0]) * deltaT - blueMean;
          blueSEM += diff * diff;
        }
        blueSEM = Math.sqrt(blueSEM / ( (traces - 1) * traces));
        
        greenBlueStartMean = 0.0;
        redBlueStartMean = 0.0;
        greenBlueEndMean = 0.0;
        redBlueEndMean = 0.0;
        greenBlueStartSEM = 0.0;
        redBlueStartSEM = 0.0;
        greenBlueEndSEM = 0.0;
        redBlueEndSEM = 0.0; 
        
        // Calculate the green-to-blue average offsets.
        for (int i = 0; i < traces; i++) {
          greenBlueStartMean += (blueEndpoints[i][0] - greenEndpoints[i][0]) * deltaT;
          greenBlueEndMean += (blueEndpoints[i][1] - greenEndpoints[i][1]) * deltaT;
        }
        greenBlueStartMean /= traces;
        greenBlueEndMean /= traces;
        
        // Calculate the green-to-blue SEM values.
        for (int i = 0; i < traces; i++) {
          diffStart = (blueEndpoints[i][0] - greenEndpoints[i][0]) * deltaT - greenBlueStartMean;
          greenBlueStartSEM += diffStart * diffStart;
          diffEnd = (blueEndpoints[i][1] - greenEndpoints[i][1]) * deltaT - greenBlueEndMean;
          greenBlueEndSEM += diffEnd * diffEnd;
        }
        greenBlueStartSEM = Math.sqrt(greenBlueStartSEM / ( (traces - 1) * traces));
        greenBlueEndSEM = Math.sqrt(greenBlueEndSEM / ( (traces - 1) * traces));
        
        // Calculate the red-to-blue average offsets.
        for (int i = 0; i < traces; i++) {
          redBlueStartMean += (blueEndpoints[i][0] - redEndpoints[i][0]) * deltaT;
          redBlueEndMean += (blueEndpoints[i][1] - redEndpoints[i][1]) * deltaT;
        }
        redBlueStartMean /= traces;
        redBlueEndMean /= traces;
        
        // Calculate the red-to-blue SEM values.
        for (int i = 0; i < traces; i++) {
          diffStart = (blueEndpoints[i][0] - redEndpoints[i][0]) * deltaT - redBlueStartMean;
          redBlueStartSEM += diffStart * diffStart;
          diffEnd = (blueEndpoints[i][1] - redEndpoints[i][1]) * deltaT - redBlueEndMean;
          redBlueEndSEM += diffEnd * diffEnd;
        }
        redBlueStartSEM = Math.sqrt(redBlueStartSEM / ( (traces - 1) * traces));
        redBlueEndSEM = Math.sqrt(redBlueEndSEM / ( (traces - 1) * traces));
      }
      
      // Populate the offsetValues ResultsTable.
      offsetValues.incrementCounter();
      
      offsetValues.addValue("G Width", greenMean);
      offsetValues.addValue("G SEM", greenSEM);
      
      if (red) {
        offsetValues.addValue("R Width", redMean);
        offsetValues.addValue("R SEM", redSEM);
      }
      
      if (blue) {
        offsetValues.addValue("B Width", blueMean);
        offsetValues.addValue("B SEM", blueSEM);
      }
      
      if (red) {
        offsetValues.addValue("G-to-R Start", greenRedStartMean);
        offsetValues.addValue("G-to-R Start SEM", greenRedStartSEM);
        offsetValues.addValue("G-to-R End", greenRedEndMean);
        offsetValues.addValue("G-to-R End SEM", greenRedEndSEM);
      }
      
      if (blue) {
        offsetValues.addValue("G-to-B Start", greenBlueStartMean);
        offsetValues.addValue("G-to-B Start SEM", greenBlueStartSEM);
        offsetValues.addValue("G-to-B End", greenBlueEndMean);
        offsetValues.addValue("G-to-B End SEM", greenBlueEndSEM);
        
        offsetValues.addValue("R-to-B Start", redBlueStartMean);
        offsetValues.addValue("R-to-B Start SEM", redBlueStartSEM);
        offsetValues.addValue("R-to-B End", redBlueEndMean);
        offsetValues.addValue("R-to-B End SEM", redBlueEndSEM);
      }
        
      offsetValues.show("Offset Values");

    }
    
    //========================================================================================================================
    
    /* Finds the maximum value of a trace. */
    private double findMax(double[] trace) {
      int traceLength = trace.length;
      double max = 0.0;
      
      for (int j = 0; j < traceLength; j++) {
        if (trace[j] > max) {
          max = trace[j];
        }
      }
      
      return max;
      
    }
    
    //========================================================================================================================

    /* Returns the extrapolated start and end times for a smoothed and normalized trace. 
     * The start and end are extrapolated from the lines defined by the integer time points when the trace first
     * rises above 10% of its maximum value, and when the trace permanently sinks below 10% of its maximum value.
    */
    private double[] findEndpoints(double[] trace, int peak) {
      int traceLength = trace.length;
      int first = 0, last = traceLength - 1, count;                                         // First and last nonzero time points, and index.
      boolean found = false;
      double[] endpoints = new double[2];
      endpoints[0] = 0.0;                                                                   // Provisional start time.
      endpoints[1] = (double) (traceLength - 1);                                            // Provisional end time.
      double x1, y1, y2, slope;                                                             // Variables for extrapolating the endpoints.
      double threshold = 0.10;                                                              // Threshold for doing the extrapolation.
      double y = 0.0;                                                                       // Extrapolated start and end value.
      
      count = 0;
      while (!found) {
        if (trace[count] > 0.0) {
          first = count;
          found = true;
        }
        count++;
      }
      
      // Find the start time point, scanning forward from the first time point in the trace.
      for (int j = first; j < peak; j++) {
        if (trace[j] > threshold) {
          x1 = ((double) j) - 1.0;
          y2 = trace[j];
          if (j > 0) {
            y1 = trace[j - 1];
          }
          else {
            y1 = 0.0;
          }
          slope = y2 - y1;
          endpoints[0] = (x1 * slope + y - y1) / slope;
          endpoints[0] = Math.max(endpoints[0], (double) first);                              // Don't extrapolate beyond the start of the trace.
          break;
        }
      }
      
      count = traceLength - 1;
      while (!found) {
        if (trace[count] > 0.0) {
          last = count;
          found = true;
        }
        count--;
      }
      
      // Find the end time point, scanning backwards from the last time point in the trace.
      for (int j = last; j > peak; j--) {
        if (trace[j] > threshold) {
          x1 = ((double) j) + 1.0;
          y2 = trace[j];
          if (j < traceLength - 1) {
            y1 = trace[j + 1];
          }
          else {
            y1 = 0.0;
          }
          slope = y2 - y1;
          endpoints[1] = (x1 * slope - y + y1) / slope;
          endpoints[1] = Math.min(endpoints[1], (double) last);                              // Don't extrapolate beyond the end of the trace.
          break;
        }
      }
      
      return endpoints;
    }
        
    //========================================================================================================================

    /* Returns an array representing the numerical derivative of an integrated trace. */
    private double[] differentiate(double[] integrated, double h) {
      int arrayLength = integrated.length;
      double[] derivative = new double[arrayLength];
      double max = integrated[arrayLength - 1];                                   // Maximum final value of the integral.
      double f_minus5, f_minus4, f_minus3, f_minus2, f_minus1, f_plus1, f_plus2, f_plus3, f_plus4, f_plus5;
      
      // Use the n = 2, N = 11 formula from Holoborodko:
      // http://www.holoborodko.com/pavel/numerical-methods/numerical-derivative/smooth-low-noise-differentiators/
      // Feed in h = 1.0 because the "Integrated" values don't actually take the interval h into account.
      
      for (int j = 0; j < arrayLength; j++) {
        if (j - 5 < 0) {
          f_minus5 = 0.0;
        }
        else {
          f_minus5 = integrated[j - 5];
        }
        if (j - 4 < 0) {
          f_minus4 = 0.0;
        }
        else {
          f_minus4 = integrated[j - 4];
        }
        if (j - 3 < 0) {
          f_minus3 = 0.0;
        }
        else {
          f_minus3 = integrated[j - 3];
        }
        if (j - 2 < 0) {
          f_minus2 = 0.0;
        }
        else {
          f_minus2 = integrated[j - 2];
        }
        if (j - 1 < 0) {
          f_minus1 = 0.0;
        }
        else {
          f_minus1 = integrated[j - 1];
        }
        if (j + 1 >= arrayLength) {
          f_plus1 = max;
        }
        else {
          f_plus1 = integrated[j + 1];
        }
        if (j + 2 >= arrayLength) {
          f_plus2 = max;
        }
        else {
          f_plus2 = integrated[j + 2];
        }
        if (j + 3 >= arrayLength) {
          f_plus3 = max;
        }
        else {
          f_plus3 = integrated[j + 3];
        }
        if (j + 4 >= arrayLength) {
          f_plus4 = max;
        }
        else {
          f_plus4 = integrated[j + 4];
        }
        if (j + 5 >= arrayLength) {
          f_plus5 = max;
        }
        else {
          f_plus5 = integrated[j + 5];
        }
        
        if (integrated[j] == 0.0 || integrated[j - 1] == max) {
          derivative[j] = 0.0;                                                      // Clamp to zero if the raw trace is at zero.
        }
        else {
          derivative[j] = ( 42.0 * (f_plus1 - f_minus1) + 48.0 * (f_plus2 - f_minus2) + 27.0 * (f_plus3 - f_minus3) + 
                          8.0 * (f_plus4 - f_minus4) + f_plus5 - f_minus5 ) / (512.0 * h);
        }
      }
      
      return derivative;
    }
    
    //========================================================================================================================
    
    /* Returns the time point at which an integral reaches the designated percentage of its maximum. */
    private int integralTimePoint(double[] integrated, double percentage) {
      int arrayLength = integrated.length;
      double max = integrated[arrayLength - 1];                                   // Maximum final value of the integral.
      double distance = 101.0;                                                    // Larger than any possible calculated distance.
      double newDistance;
      int bestSoFar = 0;
      boolean found = false;
      
      if (percentage < 0.0 || percentage > 100.0) {
        IJ.showMessage("Incorrect percentage for the integral time point calculation.");
        return 0;
      }
      
      int j = 0;
      double normalized;
      while (!found && j < arrayLength) {
        normalized = 100.0 * integrated[j] / max;
        newDistance = Math.abs( normalized - percentage );
        if (newDistance < distance || (newDistance == distance && normalized < percentage)) {
          distance = newDistance;
          bestSoFar = j;
          j++;
        }
        else {
          found = true;
        }
      }
      
      return bestSoFar;
    }
    
}

/**
 * Import ST spots into QuPath project and color according to _color
 * @author Sebastian Krossa
 *
 * Based on:
 * Create a region annotation with a fixed size in QuPath, based on the current viewer location.
 * by @author Pete Bankhead
 *
 * 
 */


import com.google.gson.Gson
import com.google.gson.JsonObject
import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.PathObject
import qupath.lib.objects.classes.PathClassFactory
import qupath.lib.roi.EllipseROI
import qupath.lib.common.ColorTools
import static qupath.lib.gui.scripting.QPEx.*
import java.io.BufferedReader
import java.io.FileReader

// Define the size of the region to create
double sizeMicrons = 50.0
// downscale factor of QuPath image -> spatial transcriptomics hires img
double downscale = 5.0
// set this to true to import all spots not just the ones that have different annotations
boolean get_all = false
// set this to minimum cell type fraction to draw
float minCtFrac = 0.001


// get user input
def file_coordinates = Dialogs.promptForFile(
        "Choose Barcode coordinate file",
        null,
        "csv",
        ".csv"
)
// get the filenames that have data in this csv

def csvReader = new BufferedReader(new FileReader(file_coordinates));
Set<String> imgSet = []

// column that contains qp file names
int qpImgCol = 3
while ((row = csvReader.readLine()) != null) {
    def rowContent = row.split(",")
    imgSet.add(rowContent[qpImgCol])
}

// do this on a project lvl
def project = getProject()
for (entry in project.getImageList()) {
    if (imgSet.contains(entry.getImageName()) == true) {
        def imageData = entry.readImageData()
        def server = imageData.getServer()
        def metadata = server.getMetadata()
        def imgName = entry.getImageName()

        def combinedUpScaleFactor = downscale
        print 'combined upscale ' + combinedUpScaleFactor

        csvReader = new BufferedReader(new FileReader(file_coordinates));

        // Convert size in microns to pixels - QuPath ROIs are defined in pixel units of the full-resolution image
        //int sizePixels = Math.round(sizeMicrons / metadata.averagedPixelSize)
        Collection<PathObject> st_annotations = new ArrayList<PathObject>()

        // Loop through all the rows of the CSV file.
        // column definitions
        int spotIdCol = 0
        int qpXCol = 1
        int qpYCol = 2
        int dataCol = 4
        int colorCol = 5
        
        def dataAnnotations = [:]
        boolean firstRow = true
        while ((row = csvReader.readLine()) != null) {
            def rowContent = row.split(",")
            if (firstRow) {
                dataName = rowContent[dataCol] as String
                
                firstRow = false
            } else {
                String qpImg = rowContent[qpImgCol] as String
                if (qpImg == imgName) {
                    double cy = combinedUpScaleFactor * (rowContent[qpYCol] as double)
                    double cx = combinedUpScaleFactor * (rowContent[qpXCol] as double)
                    def key = Math.round(rowContent[dataCol] as double) as String
                    if (!(dataAnnotations.containsKey(key))) {
                        dataAnnotations[key] = new ArrayList<PathObject>()
                        print 'added key: ' + key
                    }
                    int sizePixels = Math.round(sizeMicrons / metadata.averagedPixelSize)
                    def roi = new EllipseROI(cx-sizePixels/2, cy-sizePixels/2, sizePixels, sizePixels, null)
                    dataAnnotations[key].add(new PathAnnotationObject(roi, PathClassFactory.getPathClass(String.format("ST_spots_%s_value_%s", dataName as String, key), hexToRgb(rowContent[colorCol] as String))))
                }
            }
        }
        dataAnnotations.each{it ->
            imageData.getHierarchy().addPathObjects(it.value)
            mergeAnnotations(imageData.getHierarchy(), it.value)
            //getSelectedObject().setName(ctNames[it.key])
        }
        //imageData.getHierarchy().addPathObjects(st_annotations)
        entry.saveImageData(imageData)
    }

    print entry.getImageName() + ' processed'

}


int hexToRgb(String hex)
{
    hex = hex.replace("#", "");
    return ColorTools.packRGB(
                    Integer.valueOf(hex.substring(0, 2), 16),
                    Integer.valueOf(hex.substring(2, 4), 16),
                    Integer.valueOf(hex.substring(4, 6), 16));
}

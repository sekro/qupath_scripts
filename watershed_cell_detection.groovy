/**
 * Run WatershedCellDetection via script for QuPath
 *
 * set findCellsInPathClass to name of annotation to be used as cell detection area
 * adjust cellDectectionArgString to your needs - currently uses mostly defaults with relative low threshold for H OD
 * Don't forget to estimate stain vectors prior running script
 * 
 * @author Sebastian Krossa
 * NTNU Trondheim, Norway
 * sebastian.krossa@ntnu.no
 *
 * tested with QuPath 0.2.3 http://qupath.github.io/
 */
import qupath.lib.gui.scripting.QPEx

def imageData = getCurrentImageData()
def hierarchy = imageData.getHierarchy()
def annotations = hierarchy.getAnnotationObjects()

// define annotation name to select as "borders" for cell finding
def find_cells_in_PathClass = 'tissue'
String cellDetectionArgString =String.format(
        '{"detectionImageBrightfield": "Hematoxylin OD", ' +
                '"requestedPixelSizeMicrons": 0.5, ' +
                '"backgroundRadiusMicrons": 8.0, ' +
                '"medianRadiusMicrons": 0.0, ' +
                '"sigmaMicrons": 1.5, ' +
                '"minAreaMicrons": 10.0, ' +
                '"maxAreaMicrons": 400.0, ' +
                '"threshold": 0.02, ' +
                '"maxBackground": 2.0, ' +
                '"watershedPostProcess": true, ' +
                '"cellExpansionMicrons": 5.0, ' +
                '"includeNuclei": true, ' +
                '"smoothBoundaries": true, ' +
                '"makeMeasurements": true}'
)

annotations.each {
    if (it.getPathClass().toString() == find_cells_in_PathClass) {
        QPEx.selectObjects(it)
        QPEx.runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', cellDetectionArgString)
    }
}
 print 'Done!'

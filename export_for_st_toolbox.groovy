/**
 * Export histopathology data for import with st_toolbox script for QuPath
 * test with QuPath version 0.2.3
 * @author Sebastian Krossa
 * NTNU Trondheim, Norway
 * Jan/Feb/March 2021
 * sebastian.krossa@ntnu.no
 * based on & reuses code from Pete Bankhead / QuPath
 * https://gist.github.com/petebankhead/0b14beef131312042686c01056104b85
 * https://petebankhead.github.io/qupath/scripting/2018/03/13/script-export-import-binary-masks.html
 * https://qupath.readthedocs.io/en/latest/docs/scripting/overview.html
 * https://petebankhead.github.io/
 * http://qupath.github.io/
 */

import com.google.gson.GsonBuilder
import qupath.lib.geom.Point2
import qupath.lib.objects.PathObject
import qupath.lib.regions.RegionRequest
import javax.imageio.ImageIO
import java.awt.Color
import java.awt.image.BufferedImage
import static qupath.lib.gui.scripting.QPEx.*

// Configure the following vars to your needs
// image downsample factor -> the full image gets exported!
// pathOutput -> output path inside project directory
def downsample = 5.0
def name = getProjectEntry().getImageName()
def pathOutput = buildFilePath(QPEx.PROJECT_BASE_DIR, 'st_toolbox', name)
mkdirs(pathOutput)

// Define image export type; valid values are JPG, PNG or null (if no image region should be exported with the mask)
// Note: masks will always be exported as PNG
def imageExportType = 'PNG'

// gson setup
def gson = new GsonBuilder()
        .setPrettyPrinting()
        .create()


//class for poly export
class polyExp {
    Double centroidx
    Double centroidy
    Point2[] polygon
}

//class for cell export
class cellExp {
    String name
    polyExp cell_poly
    polyExp nucleus_poly
    String type
}

//class for annotation export
class annotExp {
    String name
    String className
    polyExp shape
    String type
}

//class for export of whole slide data to JSON
class imgCellsExport {
    String name
    String imgFileBaseName
    String imgType
    Integer downsample
    Double pixelHeightMicron
    Double pixelWidthMicron
    Double avgPixelSizeMicron
    def cells = new ArrayList<cellExp>()
    def annotations = new ArrayList<annotExp>()
}


// Get the main QuPath data structures
def imageData = getCurrentImageData()
def hierarchy = imageData.getHierarchy()
def server = imageData.getServer()

// get all cell objects
def cells = hierarchy.getCellObjects()

// get annotations
def annotations = hierarchy.getAnnotationObjects()

// define region - full image in this case
def region = RegionRequest.createInstance(server.getPath(), downsample, 0, 0, server.getWidth(), server.getHeight())

// Request the BufferedImage
def img = server.readBufferedImage(region)

def imgMask = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY)

// Create a name
String baseFileName = String.format('%s_(%.2f,%d,%d,%d,%d)_',
        server.getMetadata().getName(),
        region.getDownsample(),
        region.getX(),
        region.getY(),
        region.getWidth(),
        region.getHeight()
)

def cal = server.getMetadata()
//holds export data
def export_to_json = new imgCellsExport(
        name: cal.name,
        imgFileBaseName: baseFileName,
        imgType: imageExportType.toLowerCase(),
        downsample: downsample,
        pixelHeightMicron: cal.getPixelHeightMicrons(),
        pixelWidthMicron: cal.getPixelWidthMicrons(),
        avgPixelSizeMicron: cal.averagedPixelSize
)

// iterate over cells and add binary image of nucleus to imgMask and full coordinates to export_to_json
cells.each {
    generateImageMaskAndJson(region, imgMask, it, downsample, export_to_json)
}

// Create filename & export
if (imageExportType != null) {
    def fileImage = new File(pathOutput, baseFileName + '.' + imageExportType.toLowerCase())
    ImageIO.write(img, imageExportType, fileImage)
}
// Export the cell mask
def fileMask = new File(pathOutput, baseFileName + 'nuclei_' + '-mask.png')
ImageIO.write(imgMask, 'PNG', fileMask)


// now the annotations

def sortedAnnotationsMap = sortAnnotations(annotations)

sortedAnnotationsMap.each {
    processAnnotationSet(it.key as String, it.value as Collection<PathObject>, region, img, pathOutput, baseFileName,
            downsample, export_to_json)
}



//store coordinated as json
def jsonName = String.format('%s_cell_coordinates.json', name)
def fileJson = new File(pathOutput, jsonName)
fileJson.text = gson.toJson(export_to_json)

print 'Done!'

// functions

/**
 * Add extracted nucleus region to binary imgMask and add cell info, shape & coordinates to export_to_json object.
 *
 * @param region RegionRequest object corresponding to the imgMask
 * @param imgMask mask that should be filled with all binary cell images
 * @param cellObject The cell object to export
 * @param downsample Downsample value for the export of both image region & mask
 * @param export_to_json object that collects/hold detailed info on all cells for export to JSON
 * @return
 */
def generateImageMaskAndJson(RegionRequest region, BufferedImage imgMask, PathObject cellObject, double downsample, imgCellsExport export_to_json) {
    // Extract ROI of nucleus - change nucleus to getROI() to use whole cell instead for binary mask
    def roi = cellObject.nucleus

    if (roi == null) {
        print 'Warning! No ROI for object ' + cellObject + ' - cannot export corresponding region & mask'
        return
    }
    def tmp = new cellExp(
            name: cellObject.getDisplayedName().toString(),
            cell_poly: new polyExp(
                    centroidx: cellObject.getROI().getCentroidX(),
                    centroidy: cellObject.getROI().getCentroidY(),
                    polygon:  cellObject.getROI().getAllPoints()
            ),
            nucleus_poly: new polyExp(
                    centroidx: cellObject.nucleus.getCentroidX(),
                    centroidy: cellObject.nucleus.getCentroidY(),
                    polygon:  cellObject.nucleus.getAllPoints()
            ),
            type: cellObject.getROI().getRoiType().toString()
    )
    export_to_json.cells << tmp

    // Create a mask using Java2D functionality
    def shape = RoiTools.getShape(roi)

    def g2d = imgMask.createGraphics()
    g2d.setColor(Color.WHITE)
    g2d.scale(1.0/downsample, 1.0/downsample)
    g2d.translate(-region.getX(), -region.getY())
    g2d.fill(shape)
    g2d.dispose()

}

/**
 * Generate binary masks for annotation - attach annotation object to json export class
 *
 * @param region RegionRequest object corresponding to the imgMask
 * @param imgMask the mask for this annotation group
 * @param annotationObject current annotation
 * @param downsample - downsample factor
 * @param export_to_json - the json export object
 * @return
 */
def generateMaskAndAddJsonAnnotation(RegionRequest region, BufferedImage imgMask, PathObject annotationObject,
                                     double downsample, imgCellsExport export_to_json) {
    // Extract ROI & classification name
    def roi = annotationObject.getROI()
    if (roi == null) {
        print 'Warning! No ROI for object ' + pathObject + ' - cannot export corresponding region & mask'
        return
    }
    def className = annotationObject.getPathClass() == null ? 'None' : annotationObject.getPathClass().toString()
    def annoName = annotationObject.name == null ? 'Annotation' : annotationObject.name

    def tmp = new annotExp(
            name: annoName,
            className: className,
            shape: new polyExp(
                    centroidx: annotationObject.getROI().getCentroidX(),
                    centroidy: annotationObject.getROI().getCentroidY(),
                    polygon: annotationObject.getROI().getAllPoints()
            ),
            type: annotationObject.getROI().getRoiType().toString()
    )
    export_to_json.annotations << tmp

    // Create a mask using Java2D functionality

    def shape = RoiTools.getShape(roi)
    def g2d = imgMask.createGraphics()
    g2d.setColor(Color.WHITE)
    g2d.scale(1.0/downsample, 1.0/downsample)
    g2d.translate(-region.getX(), -region.getY())
    g2d.fill(shape)
    g2d.dispose()

}

/**
 * Sort annotations based on annotation name and class
 *
 * @param annotations - unsorted Collection of annotations
 * @return LinkedHashMap with Collections of annotations with same name and class - key: class_name
 */
LinkedHashMap sortAnnotations(Collection<PathObject> annotations) {
    def result = new LinkedHashMap()
    annotations.each {
        def className = it.getPathClass() == null ? 'None' : it.getPathClass().toString()
        def annoName = it.name == null ? 'Annotation' : it.name
        def key = annoName + '_' + className
        if (result[key] == null) {
            result.put(key, new ArrayList<PathObject>())
        }
        result[key].add(it)
    }
    return result
}

/**
 * Process annotations sets generated by sortAnnotations() to generate one binary image per set and extract data
 * for JSON
 *
 * @param key - current set key
 * @param annotations - current set Collection of annotations
 * @param region - the global region request
 * @param img - global image
 * @param pathOutput - output path for data
 * @param baseFileName - common filename
 * @param downsample - global downsampling factor
 * @param export_to_json - global json object
 * @return
 */
def processAnnotationSet(String key, Collection<PathObject> annotations, RegionRequest region, BufferedImage img,
                         String pathOutput, String baseFileName, double downsample, imgCellsExport export_to_json) {
    def annotationMask = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY)
    annotations.each {
        generateMaskAndAddJsonAnnotation(region, annotationMask, it, downsample, export_to_json)
    }

    def annotationMaskFile = new File(pathOutput, baseFileName + key + '_' + '-mask.png')
    ImageIO.write(annotationMask, 'PNG', annotationMaskFile)
}



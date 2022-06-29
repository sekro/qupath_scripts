/**
 * Segment images with multiple scanns into seperate images
 * test with QuPath version 0.3.2
 * @author Sebastian Krossa
 * NTNU Trondheim, Norway
 * June 2022
 * sebastian.krossa@ntnu.no
 */



import qupath.lib.common.ColorTools
import qupath.lib.gui.commands.Commands
import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.images.ImageData
import qupath.lib.images.servers.LabeledImageServer
import qupath.lib.objects.PathObject
import qupath.lib.objects.classes.PathClassFactory
import qupath.lib.objects.hierarchy.PathObjectHierarchy
import qupath.lib.projects.ProjectImageEntry
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.interfaces.ROI
import qupath.opencv.ml.pixel.PixelClassifierTools
import qupath.lib.roi.RoiTools
import static qupath.lib.gui.scripting.QPEx.*

// -- Config START --
// downsample factors of exported images
def downsampleOverview = 10
def downsampleSingleTissue = 5

// minimum detected tissues per image to trigger multiscan processing
def multiScanMinNTissues = 4

// folder inside qupath project for exports - gets created if not exits
def imageExportSubFolderName = 'image_export'

// name of the tissue class & corresponding pixel classifier (should have same name)
def tissueClassName = "tissue"

// name of the black box class & corresponding pixel classifier (should have same name)
def blackBoxClassName = "black_box"

// tissue position map - this scripts assumes a top - down orientation
// TODO: for left-right the logic in the script has to be changed - use X instead of Y axis
// TODO: probably best to move respective code -lines 92-146- into a function for that
def tissuePositionMap = [1: 'a', 2: 'b', 3: 'c', 4: 'd']

// colors for each of the positions
def tissuePositionColors = [
        1: ColorTools.makeRGB(255, 0, 0),
        2: ColorTools.makeRGB(0, 255, 0),
        3: ColorTools.makeRGB(0, 0, 255),
        4: ColorTools.makeRGB(255, 255, 0),
]
// -- Config END --

def qupath = getQuPath()
def project = qupath.getProject()
if (project == null) {
    print 'Error - no project loaded'
    Dialogs.showErrorMessage("No Project Loaded", "Please load a project")
    return
}

// get a list of all images in current project
def images = project.getImageList()
ImageData currentImgData
PathObjectHierarchy hierarchy
int procImgs = 0

for (currentImage in images) {
    // do not process overviews & labels
    if (!(currentImage.getImageName().contains('overview')) && !(currentImage.getImageName().contains('label'))) {
        print String.format("Working on %s", currentImage.getImageName())
        currentImgData = currentImage.readImageData()
        hierarchy = currentImgData.getHierarchy()
        rootObj = hierarchy.getRootObject()
        topLvlObjs = rootObj.getChildObjects()
        runTissueDetection = true
        for (obj in topLvlObjs) {
            if (obj.getPathClass().toString().equals(tissueClassName)) {
                print "This image already contains tissue annotations - skipping tissue detection"
                runTissueDetection = false
                break
            }
        }
        if (runTissueDetection) {
            runTissueDetectionAndCleaning(currentImgData, currentImage, hierarchy, tissueClassName, blackBoxClassName)
        }
        // get the tissues
        def tissueList = new ArrayList<PathObject>([])
        for (obj in topLvlObjs) {
            if (obj.getPathClass().toString().equals(tissueClassName)) {
                tissueList.add(obj)
            }
        }
        // segment multi tissue scans and export tissue images for manual checks
        if (tissueList.size() >= multiScanMinNTissues) {
            def centroidYList = []
            def classLabelMap = [tissue_class_name: 0]
            tissueList.each {obj ->
                centroidYList.add(obj.getROI().getCentroidY())
            }
            def dist = 0.7 * (centroidYList[-1] - centroidYList[0]) / 4
            def lastY = 0.0
            def i = 0
            tissueList.each { obj ->
                if ((obj.getROI().getCentroidY() - lastY > dist) || (i == 0)) {
                    i++
                    if (i <= 4) {
                        classLabelMap[String.format('%s_%s', tissueClassName, tissuePositionMap[i])] = i
                    }
                }
                if (i <= 4) {
                    obj.setPathClass(PathClassFactory.getPathClass(String.format('%s_%s', tissueClassName, tissuePositionMap[i]), tissuePositionColors[i]))
                }
                lastY = obj.getROI().getCentroidY()
            }

            def annotationLabelServer = new LabeledImageServer.Builder(currentImgData)
                    .backgroundLabel(0, ColorTools.WHITE) // Specify background label (usually 0 or 255)
                    .addLabelsByName(classLabelMap) //Each class requires a name and a number
                    .downsample(downsampleOverview)    // Choose server resolution; this should match the resolution at which tiles are exported
                    .multichannelOutput(false) // If true, each label refers to the channel of a multichannel binary image (required for multiclass probability)
                    .build()
            // define region - full image in this case
            def region = RegionRequest.createInstance(currentImgData.getServer().getPath(), downsampleOverview, 0, 0, currentImgData.getServer().getWidth(), currentImgData.getServer().getHeight())
            pathOutput = buildFilePath(PROJECT_BASE_DIR, imageExportSubFolderName)
            mkdirs(pathOutput)
            pathOutputFile = buildFilePath(PROJECT_BASE_DIR, imageExportSubFolderName, currentImage.getImageName())
            writeImageRegion(currentImgData.getServer(), region, pathOutputFile + "_original.jpg")
            writeImageRegion(annotationLabelServer, region, pathOutputFile + "_annotationLabels.jpg")

            tissueList.eachWithIndex { anno, x ->
                roi = anno.getROI()
                def requestROI = RegionRequest.createInstance(currentImgData.getServer().getPath(), downsampleSingleTissue, roi)

                pathOutput = buildFilePath(PROJECT_BASE_DIR, imageExportSubFolderName, 'segmented', currentImage.getImageName())
                mkdirs(pathOutput)
                pathOutputFile = buildFilePath(PROJECT_BASE_DIR, imageExportSubFolderName, 'segmented', currentImage.getImageName(), anno.getPathClass().toString() + '_' + x)
                //objects with overlays as seen in the Viewer
                //writeRenderedImageRegion(currentImgData.getServer(), requestROI, pathOutput+"_rendered.tif")
                //Labeled images, either cells or annotations
                writeImageRegion(annotationLabelServer, requestROI, pathOutputFile + "_annotationLabels.png")


                //To get the image behind the objects, you would simply use writeImageRegion
                writeImageRegion(currentImgData.getServer(), requestROI, pathOutputFile + "_original.png")

            }
            procImgs++
        }
    }
}
print String.format("Done - of %s images found & processed %s, skipped non-multi, overview & label", images.size(), procImgs)


def runTissueDetectionAndCleaning(ImageData currentImgData, ProjectImageEntry currentImage, PathObjectHierarchy hierarchy, String tissue_class_name, String black_box_class_name) {
    print "Running tissue detection now"
    Commands.resetSelection(currentImgData);
    try {
        PixelClassifierTools.createAnnotationsFromPixelClassifier(currentImgData, loadPixelClassifier(tissue_class_name), 3500000.0, 100000.0, PixelClassifierTools.CreateObjectOptions.SPLIT)
    } catch (Exception ex) {
        print String.format("Exception during tissue detection: %s", ex.toString())
    }
    try {
        PixelClassifierTools.createAnnotationsFromPixelClassifier(currentImgData, loadPixelClassifier(black_box_class_name), 100000.0, 100000.0, PixelClassifierTools.CreateObjectOptions.SPLIT)
    } catch (Exception ex) {
        print String.format("Exception during black_box detection: %s", ex.toString())
    }
    def removeList = new ArrayList<PathObject>([])
    topLvlObjs = hierarchy.getRootObject().getChildObjects()
    for (obj in topLvlObjs) {
        if (obj.getPathClass().toString().equals(tissue_class_name)) {
            for (obj2 in topLvlObjs) {
                if (obj2.getPathClass().toString().equals(black_box_class_name)) {

                    currentIntersecROI = RoiTools.intersection(new ArrayList<ROI>([obj.getROI(), obj2.getROI()]))
                    if (currentIntersecROI.getArea() > 0) {
                        print String.format("Removing a blackbox tissue in %s", currentImage.getImageName())
                        removeList.add(obj)
                    }
                }
            }
        }
    }
    hierarchy.removeObjects(removeList, false)

}

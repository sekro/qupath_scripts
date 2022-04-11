/**
 * Import annotations from another Project for corresponding images
 * script for QuPath
 *
 * script checks only for image names and imports the annotations into the corresponding image of the opened project
 * WORK ON COPIES!!! Don't blame me if you delete years of work - you have been warned! :)
 *
 * @author Sebastian Krossa
 * NTNU Trondheim, Norway
 * sebastian.krossa@ntnu.no
 *
 * tested with QuPath 0.2.3 http://qupath.github.io/
 */



import com.google.gson.Gson
import com.google.gson.JsonObject
import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.io.PathIO
import java.nio.file.Paths
import static qupath.lib.gui.scripting.QPEx.*

def qupath = getQuPath()
def project = qupath.getProject()
if (project == null) {
    print 'Error - no project loaded'
    Dialogs.showErrorMessage("No Project Loaded", "Please load a project as target for imported data")
    return
}

// get a list of all images in current project
def targetImages = project.getImageList()


// CLI notification
print 'Please select source project file'

// get user input
def file_source = Dialogs.promptForFile(
        "Choose source project file",
        null,
        "Project file",
        ".qpproj"
)
def newAnnotationsName = Dialogs.showInputDialog(
        "Choose Name for imported Annotations",
        "Name:",
        "Imported_Annotation"
)
if (file_source == null)
    return

def dirDataSource = new File(file_source.getParentFile(), 'data')

// read the source project file as json - maybe there is a build in way - couldn't find one :)
def gson = new Gson()
JsonObject jsonImportProj = gson.fromJson(file_source.text, JsonObject.class)

def sourceImages = jsonImportProj.get("images").getAsJsonArray()

int counter = 0
// loop over found images in source
for (currentImg in sourceImages) {
    def currentName = currentImg.get("imageName").toString().replaceAll('\"', '')
    Boolean targetFound = false
    // find image with same name in target project
    targetImages.find {
        if (it.getImageName() == currentName) {
            print String.format("Suitable target found for %s", currentName)
            // read target ImageData & Hierarchy
            def targetImgD = it.readImageData()
            def targetH= targetImgD.getHierarchy()
            // read source Hierarchy from file
            def fileData = Paths.get(dirDataSource.getAbsolutePath(), currentImg.get("entryID").toString(), 'data.qpdata').toFile()
            if (fileData.exists()) {
                def sourceH = PathIO.readHierarchy(fileData)
                def sourceAnnotations = sourceH.getAnnotationObjects()
                // change names of annotations and add to target
                sourceAnnotations.each {
                    it.setName(newAnnotationsName)
                    targetH.addPathObject(it)
                }
                // save the target
                it.saveImageData(targetImgD)
                print String.format("Done importing %s", currentName)
                targetFound = true
                counter += 1
                return true
            } else {
                print String.format("No source data found for %s", currentName)
                return false
            }            
        }
        return false
    }
    if (!targetFound) {
        print String.format("Did not find a suitable target for %s - file skipped", currentName)
    }
}

print String.format("Job's done! Imported annotations from %d of %d image(s)", counter, sourceImages.size())
Dialogs.showMessageDialog("Import", String.format("Job's done! Imported annotations from %d of %d image(s)", counter, sourceImages.size()))

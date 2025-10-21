package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang3.StringUtils;
import org.goobi.beans.Process;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.export.dms.ExportDms;
import de.sub.goobi.export.download.ExportMets;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.XmlTools;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class MeiExportPlugin extends ExportDms implements IExportPlugin, IPlugin {

    private static final long serialVersionUID = 5380698357736450408L;
    @Getter
    private String title = "intranda_export_mei";
    @Getter
    private PluginType type = PluginType.Export;

    @Getter
    private List<String> problems;

    @Getter
    @Setter
    private boolean exportFulltext;
    @Getter
    @Setter
    private boolean exportImages;

    private static final Namespace METS_NAMESPACE = Namespace.getNamespace("mets", "http://www.loc.gov/METS/");
    private static final Namespace xlink = Namespace.getNamespace("xlink", "http://www.w3.org/1999/xlink");

    @Override
    public boolean startExport(Process process) throws IOException, InterruptedException, DocStructHasNoTypeException, PreferencesException,
            WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException, SwapException, DAOException,
            TypeNotAllowedForParentException {
        String benutzerHome = process.getProjekt().getDmsImportImagesPath();
        return startExport(process, benutzerHome);
    }

    @Override
    public boolean startExport(Process process, String destination) throws IOException, InterruptedException, DocStructHasNoTypeException,
            PreferencesException, WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, SwapException,
            DAOException, TypeNotAllowedForParentException {
        problems = new ArrayList<>();

        VariableReplacer replacer = null;
        // read mets file to test if it is readable
        try {
            Prefs prefs = process.getRegelsatz().getPreferences();
            Fileformat ff = null;
            ff = process.readMetadataFile();
            DigitalDocument dd = ff.getDigitalDocument();
            replacer = new VariableReplacer(dd, prefs, process, null);
        } catch (ReadException | PreferencesException | IOException | SwapException e) {
            log.error(e);
            problems.add("Cannot read metadata file.");
            return false;
        }

        String tempFolder = ConfigurationHelper.getInstance().getTemporaryFolder();
        // export mets file into a temporary file
        ExportMets em = new ExportMets();
        boolean success = false;
        try {
            success = em.startExport(process, tempFolder);
        } catch (PreferencesException | WriteException | DocStructHasNoTypeException | MetadataTypeNotAllowedException | ReadException
                | TypeNotAllowedForParentException | IOException | InterruptedException | ExportFileException | UghHelperException | SwapException
                | DAOException e) {
            log.error(e);
        }

        Path metsPath = Paths.get(tempFolder, process.getTitel() + "_mets.xml");

        // open generated file
        Document doc = XmlTools.readDocumentFromFile(metsPath);
        Element mets = doc.getRootElement();
        // find fileSec
        Element fileSec = mets.getChild("fileSec", METS_NAMESPACE);

        // find physical structMap
        Element physSequence = null;
        for (Element e : mets.getChildren("structMap", METS_NAMESPACE)) {
            if ("PHYSICAL".equals(e.getAttributeValue("TYPE"))) {
                physSequence = e.getChild("div", METS_NAMESPACE);
            }
        }

        // get additional fileGrp information from configuration
        List<AdditionalFileGroup> filegroups = getFilegroupConfiguration(process);
        for (AdditionalFileGroup fg : filegroups) {
            // check, if a folder check is configured
            String actualFolder = null;
            String filename = null;
            if (StringUtils.isNotBlank(fg.getFolder())) {
                actualFolder = process.getConfiguredImageFolder(fg.getFolder());
            }

            if (fg.isCheckExistence()) {
                if (StringUtils.isBlank(actualFolder) || !StorageProvider.getInstance().isDirectory(Paths.get(actualFolder))) {
                    // required folder does not exist, skip filegroup generation
                    continue;
                }

                if (fg.isFilenameFromFolder()) {
                    List<String> filesInFolder = StorageProvider.getInstance().list(actualFolder);
                    if (filesInFolder.isEmpty()) {
                        // no files to export, skip filegroup generation
                        continue;
                    } else {
                        filename = filesInFolder.get(0);
                    }
                }
            }
            // if folder exists or no check is needed:
            String path = filename == null ? fg.getPath() : fg.getPath() + filename;
            path = replacer.replace(path);
            // create new fileGrp
            Element fileGrp = new Element("fileGrp", METS_NAMESPACE);
            fileSec.addContent(fileGrp);
            fileGrp.setAttribute("USE", fg.getName());
            // create file element
            Element file = new Element("file", METS_NAMESPACE);
            fileGrp.addContent(file);

            Element flocat = new Element("FLocat", METS_NAMESPACE);
            file.addContent(flocat);

            file.setAttribute("ID", fg.getName());
            file.setAttribute("MIMETYPE", fg.getMimetype());

            flocat.setAttribute("LOCTYPE", "URL");
            flocat.setAttribute("href", path, xlink);

            // assign file id to physSequence or logical element
            Element fptr = new Element("fptr", METS_NAMESPACE);
            fptr.setAttribute("FILEID", fg.getName());
            physSequence.addContent(0, fptr);
            // copy folder, if exportImages/exportOcr is set

        }
        XmlTools.saveDocument(doc, metsPath);

        // TODO move temp mets file  (and anchor file) to its destination

        // if activated, export images / ocr files to destination
        if (exportWithImages) {
            imageDownload(process, Paths.get(destination), process.getTitel(), DIRECTORY_SUFFIX);
        }
        if (exportFulltext) {
            fulltextDownload(process, Paths.get(destination), process.getTitel(), DIRECTORY_SUFFIX);
        }

        //  export additional folder to destination
        for (AdditionalFileGroup fg : filegroups) {
            if (fg.isExportContent()) {
                Path source = Paths.get(process.getConfiguredImageFolder(fg.getFolder()));
                Path dest = Paths.get(destination, source.getParent().getFileName().toString(), source.getFileName().toString());
                StorageProvider.getInstance().copyDirectory(source, dest);
            }
        }
        return success;
    }

    private List<AdditionalFileGroup> getFilegroupConfiguration(Process process) {

        List<AdditionalFileGroup> filegroups = new ArrayList<>();

        SubnodeConfiguration config = getConfig(process);

        for (HierarchicalConfiguration hc : config.configurationsAt("additionalFileGroup")) {
            AdditionalFileGroup fg = new AdditionalFileGroup(hc);
            filegroups.add(fg);
        }

        return filegroups;
    }

    private SubnodeConfiguration getConfig(Process process) {
        String projectName = process.getProjekt().getTitel();
        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(title);
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());
        SubnodeConfiguration conf = null;

        // order of configuration is:
        // 1.) project name matches
        // 2.) project is *
        try {
            conf = xmlConfig.configurationAt("//config[./project = '" + projectName + "']");
        } catch (IllegalArgumentException e) {
            conf = xmlConfig.configurationAt("//config[./project = '*']");
        }
        return conf;
    }

}
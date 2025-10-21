package de.intranda.goobi.plugins;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.goobi.beans.ProjectFileGroup;

import lombok.Getter;
import lombok.Setter;

public class AdditionalFileGroup extends ProjectFileGroup {

    private static final long serialVersionUID = -8880290409101104147L;

    @Getter
    @Setter
    private boolean exportContent = false;

    @Getter
    @Setter
    private boolean checkExistence = false;

    @Getter
    @Setter
    private boolean filenameFromFolder = false;

    public AdditionalFileGroup(HierarchicalConfiguration hc) {
        super();

        setMimetype(hc.getString("@mimetype", ""));
        setName(hc.getString("@name"));
        setPath(hc.getString("@path", ""));

        setSuffix(hc.getString("@suffix", ""));
        setFolder(hc.getString("/folder", ""));

        exportContent = hc.getBoolean("/folder/@exportContent", false);
        checkExistence = hc.getBoolean("/folder/@checkExistence", false);
        filenameFromFolder = hc.getBoolean("/folder/@filenameFromFolder", false);
    }
}

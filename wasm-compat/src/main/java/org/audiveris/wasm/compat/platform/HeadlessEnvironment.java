// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.audiveris.wasm.compat.resources.EmbeddedResources;

/** Logical paths only: storage is supplied by the backend/host, never by Windows/XDG discovery. */
public final class HeadlessEnvironment {
    private HeadlessEnvironment() { }
    public static void initialize() { System.setProperty("file.encoding", "UTF-8"); System.setProperty("java.awt.headless", "true"); }
    public static String string(String field) {
        switch (field) {
            case "OS_NAME": return "webassembly";
            case "OS_ARCH": return "wasm";
            case "FILE_ENCODING": return "UTF-8";
            case "FILE_SEPARATOR": return "/";
            case "LINE_SEPARATOR": return "\n";
            case "FLATPAK_ID": case "STD_OUT_ERR": return null;
            default: throw new IllegalArgumentException("Review WellKnowns string: " + field);
        }
    }
    public static URI uri(String field) {
        try {
            URI resources = EmbeddedResources.systemResource("res").toURI();
            if (field.equals("RES_URI")) { return resources; }
            if (field.equals("CLASS_CONTAINER")) { return resources.resolve("../"); }
            throw new IllegalArgumentException("Review WellKnowns URI: " + field);
        } catch (URISyntaxException error) { throw new IllegalStateException(error); }
    }
    public static Path path(String field) {
        switch (field) {
            case "CONFIG_FOLDER": return Paths.get("/audiveris/config");
            case "DATA_FOLDER": case "DEFAULT_BASE_FOLDER": return Paths.get("/audiveris/data");
            case "DOC_FOLDER": return Paths.get("/audiveris/data/www");
            case "EXAMPLES_FOLDER": return Paths.get("/audiveris/data/examples");
            case "TRAIN_FOLDER": return Paths.get("/audiveris/config/train");
            case "LOG_FOLDER": return Paths.get("/audiveris/log");
            case "TEMP_FOLDER": return Paths.get("/audiveris/log/temp");
            default: throw new IllegalArgumentException("Review WellKnowns path: " + field);
        }
    }
}

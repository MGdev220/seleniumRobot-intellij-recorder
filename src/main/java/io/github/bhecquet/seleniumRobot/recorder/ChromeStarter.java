package io.github.bhecquet.seleniumRobot.recorder;


import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ChromeStarter {

    private static final String EXE_EXT_QUOTE = ".exe\"";

    public String getChromePath() {
        // main chrome version
        String chromePath = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, "Software\\Classes\\ChromeHTML\\shell\\open\\command", "");
        return chromePath.split(EXE_EXT_QUOTE)[0].replace("\"", "") + ".exe";
    }

    public Path getExtensionPath() throws IOException {

        Path extensionPath = Paths.get(System.getenv("LOCALAPPDATA"), "recorder1");
        Files.createDirectories(extensionPath);

        return extensionPath;
    }

    /**
     * Unpack extension to extension folder
     *
     * @return
     * @throws IOException
     */
    private String unpackExtension() throws IOException {

        Path extensionPath = getExtensionPath();
        Path zipExtensionPath = extensionPath.resolve("recorder1.zip");

        InputStream seleniumIdeStream = ChromeStarter.class.getResourceAsStream("/recorder1.zip");
        try (InputStream is = seleniumIdeStream) {
            Files.copy(is, zipExtensionPath);
        } catch (IOException e) {
            // An error occurred copying the resource
        }

        unpackExtension(zipExtensionPath.toString(), extensionPath.toFile());
        zipExtensionPath.toFile().delete();

        return getExtensionPath().toString();
    }

    private void unpackExtension(String extensionPath, File unpackPath) throws IOException {

        byte[] buffer = new byte[1024];
        ZipInputStream zis = new ZipInputStream(new FileInputStream(extensionPath));
        ZipEntry zipEntry = zis.getNextEntry();
        while (zipEntry != null) {
            File newFile = newFile(unpackPath, zipEntry);
            if (zipEntry.isDirectory()) {
                if (!newFile.isDirectory() && !newFile.mkdirs()) {
                    throw new IOException("Failed to create directory " + newFile);
                }
            } else {
                // fix for Windows-created archives
                File parent = newFile.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Failed to create directory " + parent);
                }

                // write file content
                FileOutputStream fos = new FileOutputStream(newFile);
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
            }
            zipEntry = zis.getNextEntry();
        }

        zis.closeEntry();
        zis.close();
    }

    public File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }

    /**
     * Start chrome
     *
     * @param chromePath
     * @return true in case chrome was already started. In this case, extension must be loaded manually
     * @throws IOException
     */
    public boolean startChrome(String chromePath) throws IOException {
        boolean chromeAlreadyRunning = isChromeRunning();


        String extensionPath = unpackExtension();
        System.out.println("Chemin utilisé pour charger l'extension : " + extensionPath);


        //retirer --allow-legacy-extension-manifests done
        //--load-extension ne marche plus! supposez pour le moment qu'à chaque fois il faut charger manuelement l'extension
        Runtime.getRuntime().exec(String.format("\"%s\" --load-extension=\"%s\" --no-first-run --disable-popup-blocking --no-default-browser-check --disable-translate", chromePath, extensionPath));
        return chromeAlreadyRunning;

    }

    private boolean isChromeRunning() {

        return ProcessHandle.allProcesses().map(processHandle -> processHandle.info().command().orElse("NO_COMMAND"))
                .filter(name -> name.contains("chrome") && !name.contains("chromedriver"))
                .count() > 0;

    }

    public static void main(String[] args) {
        try {
            ChromeStarter starter = new ChromeStarter();
            starter.startChrome(starter.getChromePath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }


}

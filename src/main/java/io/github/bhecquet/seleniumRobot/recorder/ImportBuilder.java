package io.github.bhecquet.seleniumRobot.recorder;

import java.util.*;
import java.util.stream.Collectors;

public class ImportBuilder {

    private static final Map<String, String> TYPE_TO_IMPORT = new HashMap<>();

    static {
        TYPE_TO_IMPORT.put("HtmlElement", "com.seleniumtests.uipage.htmlelements.HtmlElement");
        TYPE_TO_IMPORT.put("TextFieldElement", "com.seleniumtests.uipage.htmlelements.TextFieldElement");
        TYPE_TO_IMPORT.put("SelectElement", "com.seleniumtests.uipage.htmlelements.SelectElement");
        TYPE_TO_IMPORT.put("CheckBoxElement", "com.seleniumtests.uipage.htmlelements.CheckBoxElement");
        TYPE_TO_IMPORT.put("ButtonElement", "com.seleniumtests.uipage.htmlelements.ButtonElement");
        TYPE_TO_IMPORT.put("LinkElement", "com.seleniumtests.uipage.htmlelements.LinkElement");
        TYPE_TO_IMPORT.put("FrameElement", "com.seleniumtests.uipage.htmlelements.FrameElement");
        TYPE_TO_IMPORT.put("ListSelect", "com.seleniumtests.uipage.htmlelements.select.*");
        TYPE_TO_IMPORT.put("ImageElement", "com.seleniumtests.uipage.htmlelements.ImageElement");
        TYPE_TO_IMPORT.put("PictureElement", "com.seleniumtests.uipage.htmlelements.PictureElement");
        TYPE_TO_IMPORT.put("PasswordFieldElement", "com.seleniumtests.uipage.htmlelements.PasswordFieldElement");
        TYPE_TO_IMPORT.put("TextAreaElement", "com.seleniumtests.uipage.htmlelements.TextAreaElement");
        TYPE_TO_IMPORT.put("OptionElement", "com.seleniumtests.uipage.htmlelements.OptionElement");
        TYPE_TO_IMPORT.put("HeadingElement", "com.seleniumtests.uipage.htmlelements.HeadingElement");
        TYPE_TO_IMPORT.put("DialogElement", "com.seleniumtests.uipage.htmlelements.DialogElement");
        TYPE_TO_IMPORT.put("TableElement", "com.seleniumtests.uipage.htmlelements.TableElement");
        TYPE_TO_IMPORT.put("TableRowElement", "com.seleniumtests.uipage.htmlelements.TableRowElement");


        TYPE_TO_IMPORT.put("By", "org.openqa.selenium.By");
        TYPE_TO_IMPORT.put("Keys", "org.openqa.selenium.Keys");
    }

    public static Set<String> computeImports(List<SeleniumAction> actions) {
        Set<String> imports = new HashSet<>();

        for (SeleniumAction a : actions) {

            if (a.getFramePath() != null && !a.getFramePath().isEmpty()) {
                addImport(imports, "FrameElement");
            }

            // Import de l’élément détecté
            addImport(imports, a.getElementType());

            // Import Selenium By
            if (a.getSelector().startsWith("By.")) {
                addImport(imports, "By");
            }

            //  ByC (SeleniumRobot)
            if (a.getSelector().contains("ByC.")) {
                imports.add("import com.seleniumtests.uipage.ByC;");
            }

            // Import Keys (sendKeys)
            if (a.getFormattedCommand().contains("Keys.")) {
                addImport(imports, "Keys");
            }
        }

        return imports.stream().sorted().collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static void addImport(Set<String> imports, String type) {
        String fqn = TYPE_TO_IMPORT.get(type);
        if (fqn != null) imports.add("import " + fqn + ";");
    }


}

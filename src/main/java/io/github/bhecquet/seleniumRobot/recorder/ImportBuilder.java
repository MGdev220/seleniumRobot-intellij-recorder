package io.github.bhecquet.seleniumRobot.recorder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        TYPE_TO_IMPORT.put("SelectList", "com.seleniumtests.uipage.htmlelements.SelectList");
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
        Set<String> fqns = new java.util.HashSet<>();

        for (SeleniumAction a : actions) {
            if (a == null) continue;

            if (a.getFramePath() != null && !a.getFramePath().isEmpty()) {
                addFqn(fqns, "FrameElement");
            }
            addFqn(fqns, a.getElementType());

            String sel = null;
            try {
                sel = a.getSelector();
            } catch (Exception ignore) {
            }
            if (sel != null) {
                if (sel.startsWith("By.")) addFqn(fqns, "By");
                if (sel.contains("ByC.")) fqns.add("com.seleniumtests.uipage.ByC");
            }
            String cmd = a.getFormattedCommand();
            if (cmd != null && cmd.contains("Keys.")) addFqn(fqns, "Keys");
        }

        return fqns.stream()
                .sorted()
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static void addFqn(Set<String> set, String type) {
        if (type == null) return;
        String fqn = TYPE_TO_IMPORT.get(type);
        if (fqn != null) set.add(fqn);
    }

    private static void addImport(Set<String> imports, String type) {
        String fqn = TYPE_TO_IMPORT.get(type);
        if (fqn != null) imports.add("import " + fqn + ";");
    }


}

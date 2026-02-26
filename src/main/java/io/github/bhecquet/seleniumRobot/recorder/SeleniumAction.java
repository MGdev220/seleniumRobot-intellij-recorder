package io.github.bhecquet.seleniumRobot.recorder;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SeleniumAction {
    private String command;
    private String value;
    private List<List<String>> targets;
    private List<FrameInfo> framePath;


    public void setFramePath(List<FrameInfo> framePath) {
        this.framePath = framePath;
    }

    public List<FrameInfo> getFramePath() {
        return framePath;
    }

    public String getCommand() {
        return command;
    }


    public String getFormattedCommand() {
        String action = null;
        String formattedValue = null;

        switch (command) {
            case "type":
                action = "sendKeys";
                formattedValue = "\"" + escape(value) + "\"";
                break;

            case "sendKeys":
            case "keydown":
            case "keyup":
                action = "sendKeys";
                formattedValue = formatKeyValue(value); // ${KEY_ENTER} -> Keys.ENTER
                break;

            case "doubleClick":
                action = "doubleClickAction";
                break;

            case "click":
            case "check":
            case "uncheck":
                action = "click";
                break;

            case "dragAndDropToObject":
                action = "dragAndDropTo";
                formattedValue = "";
                break;


            case "select":

                if (value != null && value.startsWith("label=")) {
                    action = "selectByText";
                    formattedValue = "\"" + escape(value.replace("label=", "").trim()) + "\"";

                } else if (value != null && value.startsWith("value=")) {
                    action = "selectByValue";
                    formattedValue = "\"" + escape(value.replace("value=", "").trim()) + "\"";

                } else if (value != null && value.startsWith("index=")) {
                    action = "selectByIndex";
                    formattedValue = value.replace("index=", "").trim();

                } else {

                    if (value == null || value.trim().isEmpty()) {
                        action = "selectByIndex";
                        formattedValue = "0";
                    } else {
                        action = "selectByValue";
                        formattedValue = "\"" + escape(value.trim()) + "\"";
                    }
                }
                break;


            case "removeSelection":
                if (value != null && value.startsWith("label=")) {
                    action = "deselectByText";
                    formattedValue = "\"" + escape(value.replace("label=", "").trim()) + "\"";
                } else if (value != null && value.startsWith("value=")) {
                    action = "deselectByValue";
                    formattedValue = "\"" + escape(value.replace("value=", "").trim()) + "\"";
                } else if (value != null && value.startsWith("index=")) {
                    action = "deselectByIndex";
                    formattedValue = value.replace("index=", "").trim();
                } else {
                    action = "deselectByText";
                    formattedValue = value == null ? "" : "\"" + escape(value.trim()) + "\"";
                }
                break;

            case "clickAt": // value is of the form "x,y"
                action = "clickAt";
                formattedValue = value;
                break;

            case "selectFrame":
                action = null;
                break;


            case "sw_fr":
                FrameInfo fr = framePath.get(framePath.size() - 1);
                return String.format(
                        "\t\tHtmlElement iframe = new HtmlElement(\"iframe\", %s);\n\t\tiframe.switchToFrame();\n",
                        fr.getSelector()
                );


            case "fr_root":

                return "\t\tio.github.bhecquet.seleniumrobot.core.context.SeleniumRobotContext.getWebDriver().switchTo().defaultContent();\n";


            case "fr_up":
                return "\t\tio.github.bhecquet.seleniumrobot.core.context.SeleniumRobotContext.getWebDriver().switchTo().parentFrame();\n";


            case "selectWindow":
                action = "selectNewWindow";
                break;

            default:

                action = command;
        }

        return String.format("\t\t%s.%s(%s);\n",
                getElementName(),
                action,
                formattedValue == null ? "" : formattedValue
        );
    }

    public List<SeleniumTarget> getTargets() {
        return targets.stream()
                .map(t -> new SeleniumTarget(t.get(1), t.get(0)))
                .collect(Collectors.toList());
    }

    public String getValue() {
        return value;
    }

    public String getWebElementString() {
        String elementName = getElementName();
        String elementType = getElementType();
        String selector = getSelector();

        // Ajout du nom logique (placeholder/aria/id/name plutôt que la valeur brute)

        String logicalName = "\"" + escape(firstNonEmpty(
                extractAriaLabelFromTargets(),
                extractButtonTextFromTargets(),
                extractLinkTextFromTargets(),
                extractPlaceholder(getSelector()),   // optionnel si tu gardes
                extractNameFromTargets(),
                extractIdFromTargets(),
                extractDataTestidFromTargets(),
                elementName
        )) + "\"";

        return String.format("\n\tprivate static %s %s = new %s(%s, %s);\n",
                elementType,
                elementName,
                elementType,
                logicalName,
                selector
        );
    }


    protected String getSelector() {

        SeleniumTarget best = chooseBestTarget();
        String selector = buildSelectorFromTarget(best);


        if (isInvalidSelector(selector)) {
            for (SeleniumTarget t : getTargets()) {
                selector = buildSelectorFromTarget(t);
                if (!isInvalidSelector(selector)) break;
            }
        }


        if (isInvalidSelector(selector)) {
            String elemType = getElementType();
            if ("LinkElement".equals(elemType)) {

                String linkText = extractLinkTextFromTargets();
                if (linkText != null && !linkText.isEmpty()) {
                    selector = String.format("By.linkText(\"%s\")", escape(linkText));
                } else {
                    selector = "By.cssSelector(\"a\")";
                }
            } else if ("ButtonElement".equals(elemType)) {
                selector = "By.cssSelector(\"button, input[type='button'], input[type='submit'], input[type='reset']\")";
            } else if ("ListSelect".equals(elemType)) {
                selector = "By.cssSelector(\"select\")";
            } else if ("CheckBoxElement".equals(elemType)) {
                selector = "By.cssSelector(\"input[type='checkbox']\")";
            } else if ("TextFieldElement".equals(elemType)) {
                selector = "By.cssSelector(\"input[type='text'], input[type='email'], input[type='password'], input[type='url'], input[type='tel'], input[type='number'], input[type='search'], textarea\")";
            } else {
                // Dernier recours
                selector = "By.cssSelector(\"*\")";
            }
        }

        return selector;
    }

    private boolean isInvalidSelector(String s) {
        if (s == null) return true;
        String t = s.trim();
        return t.isEmpty() || t.equals("By.cssSelector(\"*\")") || t.equals("By.cssSelector(\"css\")");
    }

    // Convertit un SeleniumTarget en By.xxx(...)
    private String buildSelectorFromTarget(SeleniumTarget target) {
        if (target == null) return null;

        String type = target.getTargetType() == null ? "" : target.getTargetType().trim();
        String raw = target.getTargetSelector() == null ? "" : target.getTargetSelector().trim();
        if (raw.isEmpty()) return null;

        switch (type) {
            case "data-testid":
            case "dataTestid":
                return String.format("By.cssSelector(\"[data-testid='%s']\")", escape(raw));

            case "id":
                return String.format("By.id(\"%s\")", escape(raw));

            case "name":
                return String.format("By.name(\"%s\")", escape(raw));

            case "linkText":
                return String.format("By.linkText(\"%s\")", escape(raw));

            case "ariaLabel":
            case "aria-label":
                return String.format("By.cssSelector(\"[aria-label='%s']\")", escape(raw));

            case "css":
            case "css:finder":
                // raw est déjà un sélecteur CSS
                if (raw.equalsIgnoreCase("css")) return null;
                return String.format("By.cssSelector(\"%s\")", escape(raw));

            case "xpath":
            case "xpath:attributes":
            case "xpath:idRelative":
            case "xpath:position":
            case "xpath:link":
            case "xpath:href":
            case "xpath:innerText":
            case "xpath:img":
                return String.format("By.xpath(\"%s\")", escape(raw));

            default:
                return null;
        }
    }


    // ------------------ ELEMENT NAME ------------------

    public String getElementName() {
        // Construire un nom explicite basé sur id/name/placeholder/aria/linkText
        String base = firstNonEmpty(
                extractIdFromTargets(),
                extractNameFromTargets(),
                extractPlaceholder(getSelector()),
                extractAriaLabel(getSelector()),
                extractLinkTextFromTargets(),

                sanitizeForIdentifier(firstTargetRawOrEmpty())
        );

        String camel = toCamelCase(sanitizeForIdentifier(base));


        String suffix = suggestSuffixFromType(getElementType());
        String name = camel + suffix;


        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            name = "_" + name;
        }
        return name;
    }


// ------------------ ELEMENT TYPE ------------------

    public String getElementType() {

        final boolean isTypingCommand = "type".equals(command) || "sendKeys".equals(command)
                || "keydown".equals(command) || "keyup".equals(command);
        if ("check".equals(command) || "uncheck".equals(command)) {
            return "CheckBoxElement";
        }

        if ("select".equals(command) || "removeSelection".equals(command)) {
            return "ListSelect";
        }

        if ("selectFrame".equals(command)) {
            return "FrameElement";
        }
        if ("linktext".equals(command)) {
            return "LinkTextElement";
        }
        if ("image".equals(command)) {
            return "ImageElement";
        }


        boolean isLink = false, isButton = false, isCheckbox = false, isSelect = false, isFrame = false, isText = false, isImage = false;

        for (SeleniumTarget t : getTargets()) {
            String raw = (t.getTargetSelector() == null) ? "" : t.getTargetSelector().toLowerCase();
            String type = (t.getTargetType() == null) ? "" : t.getTargetType().toLowerCase();


            if ("linktext".equals(type) || containsCssTag(raw, "a") || containsXpathTag(raw, "a") || containsAttribute(raw, "href")) {
                isLink = true;
            }


            if ("button".equals(type)
                    || containsCssTag(raw, "button")
                    || containsXpathTag(raw, "button")
                    || containsCssInputType(raw, "submit", "button", "reset")
                    || containsXpathInputType(raw, "submit", "button", "reset")
                    || containsAriaRole(raw, "button")) {
                isButton = true;
            }


            if (containsCssInputType(raw, "checkbox") || containsXpathInputType(raw, "checkbox") || containsAriaRole(raw, "checkbox")) {
                isCheckbox = true;
            }


            if (containsCssTag(raw, "select") || containsXpathTag(raw, "select")) {
                isSelect = true;
            }


            if (containsCssTag(raw, "iframe") || containsCssTag(raw, "frame")
                    || containsXpathTag(raw, "iframe") || containsXpathTag(raw, "frame")) {
                isFrame = true;
            }


            if (containsCssInputType(raw, "text", "email", "password", "url", "tel", "number", "search")
                    || containsXpathInputType(raw, "text", "email", "password", "url", "tel", "number", "search")
                    || containsCssTag(raw, "textarea")
                    || containsXpathTag(raw, "textarea")) {
                isText = true;
            }


            if (containsCssTag(raw, "img") || containsXpathTag(raw, "img")) {
                isImage = true;
            }
        }

        if (isLink) return "LinkElement";
        if (isButton) return "ButtonElement";
        if (isCheckbox) return "CheckBoxElement";
        if (isSelect) return "ListSelect";
        if (isFrame) return "HtmlElement";
        if (isText || isTypingCommand) return "TextFieldElement";
        if (isImage) return "ImageElement";

        return "HtmlElement";
    }

    /**
     * =========================
     * Helpers d’analyse CSS / XPath / ARIA
     * =========================
     **/

    private boolean containsCssTag(String raw, String tag) {
        if (raw == null || raw.isEmpty()) return false;
        String s = raw.startsWith("css=") ? raw.substring("css=".length()) : raw;
        s = s.replace("\"", "").replace("'", "");
        return s.matches("(?i).*(^|[\\s>+~])" + tag + "(\\b|[\\.#\\[:]).*");
    }

    private boolean containsXpathTag(String raw, String tag) {
        if (raw == null || raw.isEmpty()) return false;
        String s = raw.startsWith("xpath=") ? raw.substring("xpath=".length()) : raw;
        s = s.replace("\"", "").replace("'", "");
        return s.matches("(?i).*(//|/descendant::)" + tag + "(\\b|\\[).*");
    }

    private boolean containsCssInputType(String raw, String... types) {
        if (raw == null || raw.isEmpty()) return false;
        String s = raw.startsWith("css=") ? raw.substring("css=".length()) : raw;
        s = s.replace("\"", "").replace("'", "").toLowerCase();
        if (!s.contains("input")) return false;
        for (String t : types) {
            String tt = t.toLowerCase();
            if (s.contains("input[type=" + tt + "]")
                    || s.contains("input[type='" + tt + "']")
                    || s.contains("input[type=\"" + tt + "\"]")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsXpathInputType(String raw, String... types) {
        if (raw == null || raw.isEmpty()) return false;
        String s = raw.startsWith("xpath=") ? raw.substring("xpath=".length()) : raw;
        s = s.replace("\"", "").replace("'", "").toLowerCase();
        if (!s.contains("//input")) return false;
        for (String t : types) {
            String tt = t.toLowerCase();
            if (s.contains("@type=" + tt) || s.contains("@type='" + tt + "'") || s.contains("@type=\"" + tt + "\"")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAttribute(String raw, String attr) {
        if (raw == null) return false;
        String s = raw.toLowerCase();
        return s.contains("[" + attr.toLowerCase() + "=") || s.contains("@" + attr.toLowerCase() + "=");
    }

    private boolean containsAriaRole(String raw, String role) {
        if (raw == null) return false;
        String s = raw.toLowerCase();
        String r = role.toLowerCase();
        return s.contains("[role=" + r + "]")
                || s.contains("[role='" + r + "']")
                || s.contains("[role=\"" + r + "\"]")
                || s.contains("@role=" + r)
                || s.contains("@role='" + r + "'")
                || s.contains("@role=\"" + r + "\"");
    }

    /**
     * ------------------ HELPERS ------------------
     **/

    private SeleniumTarget chooseBestTarget() {
        List<SeleniumTarget> tgs = getTargets();
        if (tgs == null || tgs.isEmpty()) return null;

        SeleniumTarget best = tgs.get(0);
        int bestScore = score(best.getTargetType());

        for (int i = 1; i < tgs.size(); i++) {
            SeleniumTarget t = tgs.get(i);
            int s = score(t.getTargetType());
            if (s > bestScore) {
                best = t;
                bestScore = s;
            }
        }
        return best;
    }

    private int score(String type) {
        if (type == null) return -100;
        switch (type) {
            case "data-testid":
            case "dataTestid":
                return 110; // plus fort que id
            case "id":
                return 100;
            case "name":
                return 80;
            case "ariaLabel":
                return 75;
            case "linkText":
                return 60;
            case "css":
            case "css:finder":
                return 50;
            case "xpath":
            case "xpath:attributes":
                return 40;
            default:
                return 0;
        }
    }


    private String firstTargetRawOrEmpty() {
        List<SeleniumTarget> t = getTargets();
        return (t != null && !t.isEmpty()) ? t.get(0).getTargetSelector() : "";
    }

    /**
     * --- extraction attributs depuis selector/targets ---
     **/

    private String extractAfterEqual(String s) {
        int i = s.indexOf('=');
        return (i >= 0 && i < s.length() - 1) ? s.substring(i + 1).trim() : s.trim();
    }

    private String stripPrefix(String s, String prefix) {
        if (s.startsWith(prefix + "=")) return s.substring(prefix.length() + 1);
        if (s.startsWith(prefix + ":")) return s.substring(prefix.length() + 1);
        return s;
    }

    private String extractAriaLabel(String sOrSelector) {
        if (sOrSelector == null) return null;
        Matcher m1 = Pattern.compile("aria-label='\\\"['\\\"]").matcher(sOrSelector);
        if (m1.find()) return m1.group(1);
        Matcher m2 = Pattern.compile("\\[aria-label=([^\\]]+)\\]").matcher(sOrSelector);
        if (m2.find()) return m2.group(1).replace("'", "").replace("\"", "");
        return null;
    }

    private String extractPlaceholder(String selector) {
        if (selector == null) return null;
        Matcher m = Pattern.compile("placeholder='\\\"['\\\"]").matcher(selector);
        return m.find() ? m.group(1) : null;
    }

    private String extractTargetValue(String wantedType) {
        for (SeleniumTarget t : getTargets()) {
            if (t.getTargetType() != null && t.getTargetType().equals(wantedType)) {
                return t.getTargetSelector();
            }
        }
        return null;
    }

    private String extractIdFromTargets() {
        return extractTargetValue("id");
    }

    private String extractNameFromTargets() {
        return extractTargetValue("name");
    }

    private String extractLinkTextFromTargets() {
        return extractTargetValue("linkText");
    }

    private String extractAriaLabelFromTargets() {
        String v = extractTargetValue("ariaLabel");
        if (v == null) v = extractTargetValue("aria-label");
        return v;
    }

    private String extractDataTestidFromTargets() {
        String v = extractTargetValue("data-testid");
        if (v == null) v = extractTargetValue("dataTestid");
        return v;
    }

    private String extractButtonTextFromTargets() {
        return extractTargetValue("buttonText");
    }


    // --- noms & formatage ---

    private String sanitizeForIdentifier(String s) {
        if (s == null) return "element";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", ""); // enlève accents
        t = t.replace("/", "")
                .replace(":", "_")
                .replace("=", "_")
                .replace("[", "")
                .replace("]", "")
                .replace("(", "")
                .replace(")", "")
                .replace("@", "")
                .replace("'", "")
                .replace("-", "_")
                .replace(".", "")
                .replace(" ", "_")
                .replace("#", "");
        t = t.replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
        t = t.replaceAll("_+", "_").replaceAll("^_+", "").replaceAll("_+$", "");
        if (t.isEmpty()) t = "element";
        return t;
    }

    private String toCamelCase(String s) {
        String[] parts = s.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            if (i == 0) sb.append(p);
            else sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private String suggestSuffixFromType(String elementType) {
        if (elementType == null) return "";
        switch (elementType) {
            case "TextFieldElement":
                return "Field";
            case "ListSelect":
                return "Select";
            case "ButtonElement":
                return "Button";
            case "LinkElement":
                return "Link";
            case "CheckBoxElement":
                return "Checkbox";
            default:
                return "Element";
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String formatKeyValue(String v) {
        if (v == null) return "\"\"";
        if (v.startsWith("${KEY_") && v.endsWith("}")) {
            String key = v.substring("${KEY_".length(), v.length() - 1);
            return "Keys." + key;
        }
        return "\"" + escape(v) + "\"";
    }

    private String firstNonEmpty(String... vals) {
        for (String s : vals) if (s != null && !s.trim().isEmpty()) return s.trim();
        return "element";
    }
}

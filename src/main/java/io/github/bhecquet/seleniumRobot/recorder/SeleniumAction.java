package io.github.bhecquet.seleniumRobot.recorder;

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
                formattedValue = formatKeyValue(value);
                break;


            case "doubleClick":
            case "dblclick":
                action = "doubleClickAction";
                break;


            case "click":

            case "check":
                if ("CheckBoxElement".equals(getElementType())) {
                    action = "check";
                } else {
                    action = "click";
                }
                break;

            case "uncheck":
                if ("CheckBoxElement".equals(getElementType())) {
                    action = "uncheck";
                } else {
                    action = "click";
                }
                break;


            case "dragAndDropToObject":
                action = "dragAndDropTo";
                formattedValue = "";
                break;
            case "contextmenu":
                action = "contextMenuAction";
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

                    String selText = getSelectedTextSafe(); // <-- helper ci-dessous
                    if (selText != null && !selText.isBlank()) {
                        action = "selectByText";
                        formattedValue = "\"" + escape(selText.trim()) + "\"";
                    } else if (value == null || value.trim().isEmpty()) {
                        action = "selectByIndex";
                        formattedValue = "0";
                    } else {
                        action = "selectByValue";
                        formattedValue = "\"" + escape(value.trim()) + "\"";
                    }
                }
                break;

            case "change":

                if ("SelectList".equals(getElementType())) {
                    String selText = getSelectedTextSafe();
                    if (selText != null && !selText.isBlank()) {
                        action = "selectByText";
                        formattedValue = "\"" + escape(selText.trim()) + "\"";
                    } else if (value != null && !value.trim().isEmpty()) {
                        action = "selectByValue";
                        formattedValue = "\"" + escape(value.trim()) + "\"";
                    } else {
                        action = "selectByIndex";
                        formattedValue = "0";
                    }
                } else {

                    action = "click";
                    formattedValue = null;
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
                break;


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

        String frameRef = "";
        if (framePath != null && !framePath.isEmpty()) {
            FrameInfo fr = framePath.get(framePath.size() - 1);
            String frameVar = frameVarNameFrom(fr); // voir helper ci-dessous
            frameRef = ", " + frameVar;
        }


        return String.format(
                "\n\tprivate static %s %s = new %s(%s, %s%s);\n",
                elementType,
                elementName,
                elementType,
                logicalName,
                selector,
                frameRef
        );

    }

    private String computeFrameLogicalId(FrameInfo fr) {
        String id = fr.getId();
        if (id != null && !id.isBlank()) return id.trim();
        String sel = String.valueOf(fr.getSelector());
        return Integer.toHexString(sel.hashCode());
    }

    private String frameVarNameFrom(FrameInfo fr) {
        String logicalId = computeFrameLogicalId(fr);
        String sanitized = logicalId.replaceAll("[^A-Za-z0-9_]", "_");
        return "frame_" + sanitized;
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
            } else if ("SelectList".equals(elemType)) {
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


                if (raw != null && raw.matches("(mat-.*|cdk-.*|ng-.*|.*-\\d+)")) {

                    String prefix = raw.contains("-") ? raw.split("-")[0] : raw;

                    return "By.xpath(\"//*[contains(@id,'" + escape(prefix) + "')]\")";

                }

                return String.format("By.id(\"%s\")", escape(raw));


            case "name":
                return String.format("By.name(\"%s\")", escape(raw));

            case "linkText":


                String cleaned = raw.replace("\u00A0", " ")
                        .replaceAll("\\s+", " ")
                        .trim();


                if (cleaned.length() > 40 || raw.contains("\u00A0") || raw.contains("  ")) {

                    return "By.xpath(\"//*[contains(normalize-space(.),'"
                            + escape(cleaned.substring(0, Math.min(30, cleaned.length())))
                            + "')]\")";
                }

                return "By.linkText(\"" + escape(cleaned) + "\")";

            case "ariaLabel":
            case "aria-label":
                return String.format("By.cssSelector(\"[aria-label='%s']\")", escape(raw));

            case "css":
            case "css:finder":

                return buildBestByCFromCss(raw);
            case "xpath":
            case "xpath:attributes":
            case "xpath:idRelative":
            case "xpath:position":
            case "xpath:link":
            case "xpath:href":
            case "xpath:innerText":
            case "xpath:img":

                String dt = extractAttributeFromXPath(raw, "data-testid");
                if (dt != null) {
                    return "ByC.dataTestId(\"" + escape(dt) + "\")";
                }

                String aria = extractAttributeFromXPath(raw, "aria-label");
                if (aria != null && aria.length() < 40) {
                    return "ByC.ariaLabel(\"" + escape(aria) + "\")";
                }


                String href = extractAttributeFromXPath(raw, "href");

                if (href != null && href.length() > 10) {

                    String key = href.substring(href.lastIndexOf("/") + 1)
                            .replaceAll("_\\d+.*", "")
                            .replace(".html", "");

                    if (key.length() > 5) {
                        return "By.cssSelector(\"a[href*='" + escape(key) + "']\")";
                    }
                }


                String id = extractAttributeFromXPath(raw, "id");
                if (id != null && isBusinessId(id)) {
                    return "By.id(\"" + escape(id) + "\")";
                }


                if (getElementType().equals("CheckBoxElement")) {

                    String idAttr = extractAttributeFromXPath(raw, "id");
                    if (idAttr != null) {
                        return "By.id(\"" + escape(idAttr) + "\")";
                    }

                    return "By.cssSelector(\"input[type='checkbox']\")";
                }


                String title = extractAttributeFromXPath(raw, "title");
                if (title != null && title.length() < 50) {
                    return "By.cssSelector(\"[title='" + escape(title) + "']\")";
                }

                String text = cleanText(extractLinkTextFromTargets());

                if (raw.startsWith("//div[") || raw.contains("/div[") || raw.length() > 120) {

                    if (text != null && text.length() < 40) {
                        return "ByC.text(\"" + escape(text) + "\")";
                    }

                    return "By.xpath(\"" + escape(raw.startsWith("xpath=") ? raw.substring(6) : raw) + "\")";
                }


                if (text != null && !text.isEmpty()) {
                    return "By.xpath(\"//*[contains(normalize-space(.),'"
                            + escape(text.substring(0, Math.min(30, text.length())))
                            + "')]\")";
                }


                return "By.xpath(\"//*[contains(text(), '" + escape(getElementName()) + "')] \")";


            default:
                return null;
        }
    }

    private String cleanText(String text) {
        if (text == null) return null;

        return text.replace("\u00A0", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String extractTagFromXPath(String xpath) {
        try {
            if (xpath.startsWith("//")) {
                String tag = xpath.substring(2).split("\\[")[0];
                return tag;
            }
        } catch (Exception ignored) {
        }

        return "*";
    }

    private String extractAttributeFromXPath(String raw, String attributeName) {
        if (raw == null) return null;

        // enlever prefix xpath=
        if (raw.startsWith("xpath=")) raw = raw.substring(6);

        Pattern p = Pattern.compile(attributeName + "\\s*=\\s*['\"]([^'\"]+)['\"]");
        Matcher m = p.matcher(raw);
        if (m.find()) return m.group(1);

        return null;
    }


    private String buildBestByCFromCss(String css) {
        if (css == null) return null;
        if (css.startsWith("css=")) css = css.substring(4);
        String s = css.trim();

        // 0) REFUSER les paths fragiles (dom path)
        if (looksLikeDomPath(s)) {
            return null;
        }

        // 1) #id => By.id
        if (s.matches("^#([A-Za-z0-9_-]+)$")) {
            return String.format("By.id(\"%s\")", escape(s.substring(1)));
        }

        // 2) [id='x'] => By.id
        Matcher idMatcher = Pattern.compile("\\[id=['\"]?([^'\"\\]]+)['\"]?\\]").matcher(s);
        if (idMatcher.find()) {
            return String.format("By.id(\"%s\")", escape(idMatcher.group(1)));
        }

        // 3) [name='x'] => By.name
        Matcher nameMatcher = Pattern.compile("\\[name=['\"]?([^'\"\\]]+)['\"]?\\]").matcher(s);
        if (nameMatcher.find()) {
            return String.format("By.name(\"%s\")", escape(nameMatcher.group(1)));
        }

        // 4) [data-testid='x'] => SeleniumRobot (ByC)
        Matcher dtMatcher = Pattern.compile("\\[data-testid=['\"]?([^'\"\\]]+)['\"]?\\]").matcher(s);
        if (dtMatcher.find()) {
            return "ByC.dataTestId(\"" + escape(dtMatcher.group(1)) + "\")";
        }

        // 5) [aria-label='x'] => SeleniumRobot (ByC)
        Matcher ariaMatcher = Pattern.compile("\\[aria-label=['\"]?([^'\"\\]]+)['\"]?\\]").matcher(s);
        if (ariaMatcher.find()) {
            return "ByC.ariaLabel(\"" + escape(ariaMatcher.group(1)) + "\")";
        }

        // 6) [role='x'] => css attribut court (acceptable)
        Matcher roleMatcher = Pattern.compile("\\[role=['\"]?([^'\"\\]]+)['\"]?\\]").matcher(s);
        if (roleMatcher.find()) {
            return String.format("By.cssSelector(\"[role='%s']\")", escape(roleMatcher.group(1)));
        }

        // 7) tag simple => By.tagName
        if (s.matches("^[A-Za-z][A-Za-z0-9_-]*$")) {
            return String.format("By.tagName(\"%s\")", escape(s));
        }

        // 8) Sinon: refuser
        return null;
    }

    private boolean looksLikeDomPath(String css) {
        if (css == null) return true;
        String c = css.toLowerCase(Locale.ROOT);

        // nth-of-type => fragile
        if (c.contains("nth-of-type")) return true;

        // trop profond (espaces ou >)
        int depth = c.split("\\s+|>").length;
        if (depth > 3) return true;

        // bruit Angular / Material
        if (c.contains("ng-star-inserted")) return true;

        // très long => généralement un path
        if (c.length() > 80) return true;

        return false;
    }


    // ------------------ ELEMENT NAME ------------------

    private boolean isBusinessId(String id) {
        if (id == null) return false;


        return !id.matches(
                "(mat-.*|cdk-.*|ng-.*|.*-\\d+)"

        );
    }


    public String getElementName() {

        String text = extractLinkTextFromTargets();


        if (text != null) {

            text = cleanText(text);


            String[] words = text.split(" ");
            String shortText = String.join(" ",
                    java.util.Arrays.copyOfRange(words, 0, Math.min(words.length, 5)));

            return toCamelCase(sanitizeForIdentifier(shortText)) + "Link";
        }


        String id = extractIdFromTargets();
        if (!isBusinessId(id)) {
            id = null;
        }

        String base = firstNonEmpty(
                id,
                extractNameFromTargets(),
                extractAriaLabel(getSelector()),
                extractLinkTextFromTargets(),
                normalizeCssBasedName(stripGenericTokens(firstTargetRawOrEmpty()))
        );


        String camel = toCamelCase(sanitizeForIdentifier(base));
        if (camel.isEmpty() || !Character.isJavaIdentifierStart(camel.charAt(0))) {
            camel = "_" + camel;
        }

        String suffix = suggestSuffixFromType(getElementType());

        // ---- Nouveau : déterminer si le sélecteur est FORT ----
        String selector = String.valueOf(getSelector());
        boolean strongSelector =
                selector.startsWith("By.id(")
                        || selector.startsWith("By.name(")
                        || selector.startsWith("By.linkText(")
                        || selector.contains("[data-testid=")
                        || selector.contains("[aria-label=")
                        || selector.contains("href=");

        if (strongSelector) {

            return camel + suffix;
        }

        String uniq = shortHash(selector);
        return camel + suffix + "_" + uniq;
    }


    private String stripGenericTokens(String s) {
        if (s == null) return "element";
        String t = s.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("nth[- ]?of[- ]?type\\s*\\d+", "")
                .replaceAll("\\b(div|span|app|container|row|col|section|article|list|item|cell|td|tr)\\b", " ")
                .replaceAll("[#\\[\\]\\(\\)\\.@=:>'\"/]+", " ")
                .replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? "element" : t;
    }

    private String normalizeCssBasedName(String raw) {
        if (raw == null) {
            return null;
        }

        // supprime les nth-of-type, indices, wrappers Angular
        String s = raw
                .replaceAll("nthOfType\\d+", "")
                .replaceAll("\\d+", "")
                .replaceAll("ngStarInserted", "")
                .replaceAll("ngInserted", "");


        List<String> keywords = List.of(
                "grid", "tree", "row", "cell", "node", "menu", "item", "panel", "list", "tab"
        );

        for (String k : keywords) {
            if (s.toLowerCase().contains(k)) {
                return k;
            }
        }

        // dernier recours très court
        return "element";
    }


    private String sanitizeForIdentifier(String s) {
        if (s == null) return "element";
        String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^A-Za-z0-9_ ]", " ")
                .replaceAll("\\s+", "_").replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "").toLowerCase(java.util.Locale.ROOT);
        return t.isEmpty() ? "element" : t;
    }

    private String shortHash(String data) {
        int h = (data == null ? 0 : data.hashCode());
        String hex = Integer.toHexString(h);
        return (hex.length() > 4) ? hex.substring(hex.length() - 4) : hex;
    }

    private String crop(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) : s;
    }
// ------------------ ELEMENT TYPE ------------------


    /**
     * =========================
     * Helpers d’analyse CSS / XPath / ARIA
     * =========================
     **/

    private String selectedText;

    public String getSelectedTextSafe() {
        return selectedText;
    }


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

    private boolean isSelectFromTargets() {
        for (SeleniumTarget t : getTargets()) {
            String raw = (t.getTargetSelector() == null) ? "" : t.getTargetSelector().toLowerCase();
            if (containsCssTag(raw, "select") || containsXpathTag(raw, "select")) {
                return true;
            }
        }
        return false;
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
            case "SelectList":
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

    public String getElementType() {

        final boolean isTypingCommand = "type".equals(command) || "sendKeys".equals(command)
                || "keydown".equals(command) || "keyup".equals(command);
        if ("check".equals(command) || "uncheck".equals(command)) {
            return "CheckBoxElement";
        }


        for (SeleniumTarget t : getTargets()) {
            String raw = t.getTargetSelector() == null ? "" : t.getTargetSelector().toLowerCase();

            if (containsCssInputType(raw, "checkbox") || containsXpathInputType(raw, "checkbox")) {
                return "CheckBoxElement";
            }
        }


        if ("select".equals(command) || "change".equals(command)) {
            return "SelectList";
        }


        String selector_ = String.valueOf(getSelector()).toLowerCase();
        if (selector_.contains("select") || selector_.contains("dropdown")) {
            return "SelectList";
        }


        for (SeleniumTarget t : getTargets()) {
            String raw = t.getTargetSelector() == null ? "" : t.getTargetSelector().toLowerCase();

            if (containsCssTag(raw, "select") || containsXpathTag(raw, "select")) {
                return "SelectList";
            }
        }


        if ("change".equals(command)) {


            boolean looksLikeSelect = selector_.contains("dropdown")
                    || selector_.matches(".*By\\.(id|name)\\(\".*(select|dropdown).*\"\\).*");
            if (looksLikeSelect || isSelectFromTargets()) {
                return "SelectList";
            }
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
        if (isSelect) return "SelectList";
        if (isFrame) return "FrameElement";
        if (isText || isTypingCommand) return "TextFieldElement";
        if (isImage) return "ImageElement";


        return "HtmlElement";
    }


}

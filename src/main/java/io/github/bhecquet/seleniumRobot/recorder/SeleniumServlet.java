package io.github.bhecquet.seleniumRobot.recorder;

import com.google.gson.Gson;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;


public class SeleniumServlet extends HttpServlet {

    private Project project;

    private static final Logger LOG = Logger.getInstance(SeleniumServlet.class);

    private static final java.util.concurrent.ConcurrentMap<String, String> SELECTOR_TO_VAR = new java.util.concurrent.ConcurrentHashMap<>();

    private String keyFor(String selectorLiteral, String frameVarOrNull) {
        return (frameVarOrNull == null ? "_" : frameVarOrNull) + "|" + selectorLiteral.trim();
    }

    /**
     * Extrait "import org..." from doc; util si besoin (optionnel)
     */
    private String getText(Editor editor) {
        return editor.getDocument().getText();
    }


    public SeleniumServlet(Project project) {
        this.project = project;
    }


    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {

        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor != null) {

            try {
                SeleniumAction action = new Gson().fromJson(request.getReader(), SeleniumAction.class);

                LOG.info("[Recorder] POST /event reçu");
                LOG.info("[Recorder] command=" + action.getCommand()
                        + " element=" + action.getElementName()
                        + " type=" + action.getElementType()
                        + " selector=" + action.getSelector()
                        + " value=" + action.getValue());

                if (action.getFramePath() != null) {
                    LOG.info("[Recorder] framePath size=" + action.getFramePath().size());
                }

                LOG.info("[Recorder] insertImports()");

                insertImports(editor, action);

                LOG.info("[Recorder] insertElement() -> " + action.getElementName());

                insertElement(editor, action);

                LOG.info("[Recorder] insertElementAction() -> " + action.getFormattedCommand().trim());
                insertElementAction(editor, action);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(200);
        try {
            response.getOutputStream().print("OK");
            response.getOutputStream().flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String insertElement(Editor editor, SeleniumAction seleniumAction) {

        WriteCommandAction.runWriteCommandAction(project, () -> {

            var doc = editor.getDocument();
            String text = doc.getText();

            int classOpen = text.indexOf('{');
            if (classOpen < 0) {
                return;
            }
            int classBodyStart = indexAfterLineEnd(text, classOpen);

            int framesBlockEnd = findAfterLastFrameDeclLineEnd(text, classBodyStart);


            if (seleniumAction.getFramePath() != null && !seleniumAction.getFramePath().isEmpty()) {

                FrameInfo fr = seleniumAction.getFramePath().get(seleniumAction.getFramePath().size() - 1);

                String frameVarName = computeFrameVarName(fr);
                String selector = fr.getSelector();
                if (selector == null || selector.trim().isEmpty() || "null".equals(selector.trim())) {
                    selector = "By.cssSelector(\"iframe\")";
                }

                String logicalId = computeFrameLogicalId(fr); // ex: "testFrame" ou "33c587"
                String frameDecl =
                        "\tprivate static FrameElement " + frameVarName +
                                " = new FrameElement(\"" + logicalId + "\", " + selector + ");\n";

                if (!containsExactFrameDecl(text, frameVarName)) {
                    int insertPos = (framesBlockEnd != -1) ? framesBlockEnd : classBodyStart;
                    doc.insertString(insertPos, frameDecl);

                    // refresh du texte + recalcul du bloc frames
                    text = doc.getText();
                    framesBlockEnd = findAfterLastFrameDeclLineEnd(text, classBodyStart);
                }
            }


            String elementCode = seleniumAction.getWebElementString();
            String elementType = seleniumAction.getElementType();
            String elementName = seleniumAction.getElementName();


            String selectorLiteral = seleniumAction.getSelector();
            String frameVar = null;
            if (seleniumAction.getFramePath() != null && !seleniumAction.getFramePath().isEmpty()) {
                FrameInfo fr = seleniumAction.getFramePath().get(seleniumAction.getFramePath().size() - 1);
                frameVar = computeFrameVarName(fr);
            }


            Object[] existing = findFieldDeclBySelector(text, selectorLiteral, frameVar);

            if (existing != null) {


                String existingSelector = (existing.length >= 5) ? (String) existing[4] : null;

                boolean existingStrong = isStrongSelector(existingSelector);
                boolean incomingStrong = isStrongSelector(selectorLiteral);

                if (existingStrong && incomingStrong) {

                    String oldName = (String) existing[3];
                    SELECTOR_TO_VAR.put(keyFor(selectorLiteral, frameVar), oldName);
                    return;
                }

            }
            if (existing != null) {
                int start = (int) existing[0];
                int end = (int) existing[1];
                String oldType = (String) existing[2];
                String oldName = (String) existing[3];


                boolean needUpgrade =
                        "HtmlElement".equals(oldType)
                                && ("TextFieldElement".equals(elementType)
                                || "PasswordFieldElement".equals(elementType)
                                || "TextAreaElement".equals(elementType));

                if (needUpgrade) {
                    String newText = replaceFieldType(text, start, end, oldType, elementType);
                    editor.getDocument().setText(newText);


                    SELECTOR_TO_VAR.put(keyFor(selectorLiteral, frameVar), oldName);


                    var d2 = editor.getDocument();
                    String upd2 = d2.getText();
                    upd2 = reformatFieldZone(upd2);
                    d2.setText(upd2);

                    return;
                }


                SELECTOR_TO_VAR.put(keyFor(selectorLiteral, frameVar), oldName);
                return;
            }


            if (!containsExactElementDecl(text, elementType, elementName)) {
                int insertPos = (framesBlockEnd != -1) ? framesBlockEnd : classBodyStart;
                elementCode = elementCode.replaceFirst("^\\n+", ""); // pas de \n en tête
                doc.insertString(insertPos, elementCode);
                text = doc.getText();
            }
            SELECTOR_TO_VAR.put(keyFor(selectorLiteral, frameVar), elementName);


            var document = editor.getDocument();
            String updated = document.getText();
            updated = reformatFieldZone(updated);
            document.setText(updated);
        });

        return null;
    }

    /* ======== Helpers d'insertion sûrs ======== */


    /**
     * Renvoie la fin de ligne suivant la dernière déclaration de FrameElement, ou -1 si aucun.
     */
    private int findAfterLastFrameDeclLineEnd(String text, int searchStart) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?m)^\\s*private\\s+static\\s+FrameElement\\s+\\w+\\s*=\\s*new\\s+FrameElement\\s*\\([^;]*\\);\\s*$"
        );
        java.util.regex.Matcher m = p.matcher(text);
        int afterLineEnd = -1;
        while (m.find(searchStart)) {
            int lineEnd = text.indexOf('\n', m.end());
            afterLineEnd = (lineEnd == -1) ? text.length() : lineEnd + 1;
            searchStart = m.end();
        }
        return afterLineEnd;
    }

    /**
     * Détecte la déclaration exacte du frame (précis, pour éviter les faux positifs).
     */
    private boolean containsExactFrameDecl(String text, String frameVarName) {
        String pattern = "(?m)^\\s*private\\s+static\\s+FrameElement\\s+" + java.util.regex.Pattern.quote(frameVarName) + "\\s*=";
        return java.util.regex.Pattern.compile(pattern).matcher(text).find();
    }

    /**
     * Détecte précisément la déclaration d'un élément donné.
     */
    private boolean containsExactElementDecl(String text, String elementType, String elementName) {
        String pattern = "(?m)^\\s*private\\s+static\\s+" + java.util.regex.Pattern.quote(elementType) +
                "\\s+" + java.util.regex.Pattern.quote(elementName) + "\\s*=";
        return java.util.regex.Pattern.compile(pattern).matcher(text).find();
    }

    /* ======== Nommage / ID de frame cohérents avec SeleniumAction ======== */

    /**
     * ID logique de la frame (id si dispo, sinon hash du selector)
     */
    private String buildFrameId(FrameInfo fr) {
        String id = fr.getId();
        if (id != null && !id.isBlank()) {
            return id;
        }
        String sel = String.valueOf(fr.getSelector());
        String hash = Integer.toHexString(sel.hashCode());
        return "frame_" + hash;
    }

    /**
     * Nom de variable Java pour la frame, DOIT MATCHER SeleniumAction
     */
    private String buildFrameVarName(FrameInfo fr) {
        String frameId = buildFrameId(fr);
        String sanitized = frameId.replaceAll("[^A-Za-z0-9_]", "_");
        return "frame_" + sanitized;
    }


    private boolean isStrongSelector(String selector) {
        if (selector == null) return false;
        return selector.startsWith("By.id(")
                || selector.startsWith("By.name(")
                || selector.startsWith("By.linkText(")
                || selector.contains("[data-testid=")
                || selector.contains("[aria-label=")
                || selector.contains("href="); // a[href="..."]
    }


    private void insertElementAction(Editor editor, SeleniumAction seleniumAction) {

        String cmd = seleniumAction.getCommand();
        String elementType = seleniumAction.getElementType();

        boolean isSelect = "SelectElement".equals(elementType) || "SelectList".equals(elementType);
        if (isSelect && ("click".equals(cmd) || "change".equals(cmd))) {
            return;
        }

        boolean isTypingCmd =
                "type".equals(cmd) ||
                        "sendKeys".equals(cmd) ||
                        "keyup".equals(cmd) ||
                        "keydown".equals(cmd);

        if (isTypingCmd && (seleniumAction.getValue() == null || seleniumAction.getValue().isEmpty())) {
            return;
        }

        WriteCommandAction.runWriteCommandAction(project, () -> {

            var doc = editor.getDocument();
            String text = doc.getText();
            int caretOffset = editor.getCaretModel().getCurrentCaret().getOffset();

            // --- 1) Préparation des infos
            String selectorLiteral = seleniumAction.getSelector();
            String frameVar = null;
            if (seleniumAction.getFramePath() != null && !seleniumAction.getFramePath().isEmpty()) {
                FrameInfo fr = seleniumAction.getFramePath().get(seleniumAction.getFramePath().size() - 1);
                frameVar = computeFrameVarName(fr);
            }

            String initialVar = seleniumAction.getElementName(); // ex: usernameField_b309
            String code = seleniumAction.getFormattedCommand();


            String declaredName = SELECTOR_TO_VAR.get(keyFor(selectorLiteral, frameVar));

            if (declaredName == null) {
                Object[] ex = findFieldDeclBySelector(text, selectorLiteral, frameVar);
                if (ex != null) {
                    declaredName = (String) ex[3];
                    SELECTOR_TO_VAR.put(keyFor(selectorLiteral, frameVar), declaredName);
                }
            }


            String elementNameUsed = initialVar;
            if (declaredName != null && !declaredName.equals(initialVar)) {
                code = code.replace(initialVar + ".", declaredName + ".");
                elementNameUsed = declaredName;   // ← IMPORTANT
            }


            if ("click".equals(cmd)) {
                String prevLine = previousNonEmptyLine(text, caretOffset);
                if (prevLine != null && prevLine.contains(elementNameUsed + ".click(")) {
                    return;
                }
            }


            if (isTypingCmd) {
                int[] lastRange = findLastTypingCallRange(text, caretOffset, elementNameUsed);
                if (lastRange != null) {
                    doc.replaceString(lastRange[0], lastRange[1], code);
                    editor.getCaretModel().getCurrentCaret().moveToOffset(lastRange[0] + code.length());
                    return;
                }
            }


            if ("doubleClick".equals(cmd)) {

                String prevLine = previousNonEmptyLine(text, caretOffset);

                if (prevLine != null && prevLine.contains(elementNameUsed + ".click(")) {


                    int lineStart = text.lastIndexOf('\n', text.lastIndexOf(prevLine)) + 1;
                    int lineEnd = text.indexOf('\n', lineStart);
                    if (lineEnd < 0) lineEnd = text.length();

                    doc.deleteString(lineStart, lineEnd + 1);

                    // mettre à jour caret
                    editor.getCaretModel().getCurrentCaret().moveToOffset(lineStart);

                    caretOffset = lineStart;
                }
            }

            doc.insertString(caretOffset, code);
            editor.getCaretModel().getCurrentCaret().moveToOffset(caretOffset + code.length());
        });
    }

    private String previousNonEmptyLine(String text, int caretOffset) {
        int i = Math.min(caretOffset - 1, text.length() - 1);
        while (i >= 0 && (text.charAt(i) == '\n' || text.charAt(i) == '\r')) i--;
        if (i < 0) return null;

        int lineStart = text.lastIndexOf('\n', i) + 1;
        int lineEnd = text.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = text.length();

        String line = text.substring(lineStart, lineEnd).trim();
        return line.isEmpty() ? null : line;
    }

    /**
     * Retourne {start, end} (end exclusif) de la dernière ligne contenant
     * elementName.sendKeys(...) ou elementName.setText(...) avant caretOffset.
     */
    private int[] findLastTypingCallRange(String text, int caretOffset, String elementName) {
        String p1 = elementName + ".sendKeys(";
        String p2 = elementName + ".setText(";

        int startSearch = Math.min(caretOffset, text.length());
        int i1 = text.lastIndexOf(p1, startSearch);
        int i2 = text.lastIndexOf(p2, startSearch);
        int idx = Math.max(i1, i2);
        if (idx < 0) return null;

        int lineStart = text.lastIndexOf('\n', idx) + 1;
        int lineEnd = text.indexOf('\n', idx);
        if (lineEnd < 0) lineEnd = text.length();


        String between = text.substring(lineEnd, Math.min(caretOffset, text.length()));
        if (!between.trim().isEmpty()) return null;

        int end = (lineEnd + 1 <= text.length()) ? lineEnd + 1 : lineEnd;
        return new int[]{lineStart, end};
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(HttpServletResponse.SC_OK);
    }


    private void insertImports(Editor editor, SeleniumAction seleniumAction) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            var doc = editor.getDocument();
            String content = doc.getText();

            content = content
                    .replaceFirst("(?s)^\\s+(?=package\\s+[^;]+;)", "")
                    .replaceFirst("(?m)^(\\s*package\\s+[^;]+;)[ \\t]*\\R*", "$1\n");
            doc.setText(content);
            content = doc.getText();

            var pkgPat = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+[^;]+;");
            var pkgM = pkgPat.matcher(content);
            int pkgEnd = pkgM.find() ? pkgM.end() : 0;

            var impLinePat = java.util.regex.Pattern.compile("(?m)^\\s*import\\s+([^;]+);\\s*$");
            var m = impLinePat.matcher(content);
            int firstImpStart = -1, lastImpEnd = -1;
            java.util.LinkedHashSet<String> existingFqns = new java.util.LinkedHashSet<>();
            while (m.find()) {
                if (m.start() >= pkgEnd) {
                    if (firstImpStart == -1) firstImpStart = m.start();
                    lastImpEnd = m.end();
                    existingFqns.add(m.group(1).trim());
                }
            }
            if (firstImpStart == -1) {
                firstImpStart = pkgEnd;
                lastImpEnd = pkgEnd;
            }

            java.util.Set<String> neededFqns = ImportBuilder.computeImports(
                    java.util.Collections.singletonList(seleniumAction)
            );

            java.util.Set<String> union = new java.util.TreeSet<>();
            union.addAll(existingFqns);
            union.addAll(neededFqns);

            StringBuilder canon = new StringBuilder();
            if (firstImpStart == pkgEnd) {
                canon.append("\n");
            }
            for (String fqn : union) {
                canon.append("import ").append(fqn).append(";\n");
            }

            doc.replaceString(firstImpStart, lastImpEnd, canon.toString());
        });
    }


    // ---- Helpers de mise en forme des champs (déclarations) ----

    /**
     * Remplace les séquences de \n\n\n+ par au plus une ligne vide (\n\n)
     * dans la zone des champs entre l’accolade ouvrante de classe et le début du premier method body.
     */
    private String compactBlankLinesInFieldZone(String text) {
        int classOpen = text.indexOf('{');
        if (classOpen < 0) return text;
        int zoneStart = indexAfterLineEnd(text, classOpen);


        java.util.regex.Pattern methodStartPat = java.util.regex.Pattern.compile(
                "(?m)^\\s*(public|protected|private)\\s+[\\w<>,\\s\\[\\]]+\\s+\\w+\\s*\\("
        );
        java.util.regex.Matcher mm = methodStartPat.matcher(text);
        int zoneEnd = mm.find(zoneStart) ? mm.start() : text.lastIndexOf('}'); // sinon jusqu'à la fin

        if (zoneEnd <= zoneStart) zoneEnd = text.length();

        String before = text.substring(0, zoneStart);
        String zone = text.substring(zoneStart, zoneEnd);
        String after = text.substring(zoneEnd);


        zone = zone.replaceAll("(?m)\\n{3,}", "\n\n");


        zone = zone.replaceAll(
                "(?m)(^\\s*private\\s+static[\\s\\S]*?;)[ \\t]*\\n\\n(?=\\s*private\\s+static\\s)",
                "$1\n"
        );

        return before + zone + after;
    }

    /**
     * Garantit qu’il y a exactement UNE ligne vide entre le dernier FrameElement
     * et la première déclaration non-frame (si des frames existent).
     */
    private String ensureSingleBlankAfterFrames(String text) {
        int classOpen = text.indexOf('{');
        if (classOpen < 0) return text;
        int zoneStart = indexAfterLineEnd(text, classOpen);


        java.util.regex.Pattern frameLine = java.util.regex.Pattern.compile(
                "(?m)^\\s*private\\s+static\\s+FrameElement\\s+\\w+\\s*=\\s*new\\s+FrameElement\\s*\\([^;]*\\);\\s*$"
        );
        java.util.regex.Matcher m = frameLine.matcher(text);
        int lastFrameEnd = -1;
        while (m.find(zoneStart)) {
            int lineEnd = text.indexOf('\n', m.end());
            lastFrameEnd = (lineEnd == -1) ? text.length() : lineEnd;
        }
        if (lastFrameEnd == -1) {
            return text; // pas de frames
        }

        int pos = lastFrameEnd;
        int runStart = pos;
        int runEnd = pos;

        while (runEnd < text.length() && text.charAt(runEnd) == '\n') runEnd++;


        String after = text.substring(runEnd);
        if (after.startsWith("\n")) {
            // 2+ vides -> réduire à 1
            text = text.substring(0, runStart) + "\n\n" + after.replaceFirst("^\\n+", "");
        } else if (!after.isEmpty() && after.charAt(0) != '\n') {
            // pas de blanc -> en ajouter 1
            text = text.substring(0, runStart) + "\n\n" + after;
        }
        return text;
    }

    /**
     * Renvoie l'index juste après la fin de ligne qui contient 'pos'
     */
    private int indexAfterLineEnd(String text, int pos) {
        int nl = text.indexOf('\n', pos);
        return nl >= 0 ? nl + 1 : pos + 1;
    }

    /**
     * Compacte la zone des champs (entre '{' de la classe et la première méthode):
     * - Réduit tout excès de lignes vides.
     * - Force exactement UNE ligne vide après le dernier FrameElement.
     * - Force ZERO ligne vide entre deux déclarations non-frame.
     */
    private String reformatFieldZone(String text) {
        int classOpen = text.indexOf('{');
        if (classOpen < 0) return text;

        int zoneStart = indexAfterLineEnd(text, classOpen);


        java.util.regex.Pattern methodStartPat = java.util.regex.Pattern.compile(
                "(?m)^\\s*(public|protected|private)\\s+[\\w<>,\\s\\[\\]]+\\s+\\w+\\s*\\("
        );
        java.util.regex.Matcher mm = methodStartPat.matcher(text);
        int zoneEnd = mm.find(zoneStart) ? mm.start() : text.lastIndexOf('}');
        if (zoneEnd <= zoneStart) zoneEnd = text.length();

        String before = text.substring(0, zoneStart);
        String zone = text.substring(zoneStart, zoneEnd);
        String after = text.substring(zoneEnd);


        zone = zone.replaceAll("(?m)\\n{3,}", "\n\n");

        java.util.regex.Pattern frameLine = java.util.regex.Pattern.compile(
                "(?m)^\\s*private\\s+static\\s+FrameElement\\s+\\w+\\s*=\\s*new\\s+FrameElement\\s*\\([^;]*\\);\\s*$"
        );
        java.util.regex.Pattern fieldLine = java.util.regex.Pattern.compile(
                "(?m)^\\s*private\\s+static\\s+\\w[\\w<>]*Element\\s+\\w+\\s*=\\s*new\\s+\\w[\\w<>]*Element\\s*\\([^;]*\\);\\s*$"
        );

        String[] lines = zone.split("\\R", -1);
        StringBuilder out = new StringBuilder();
        boolean seenAnyFrame = false;
        int lastFrameLineIndex = -1;


        for (int i = 0; i < lines.length; i++) {
            if (frameLine.matcher(lines[i]).find()) {
                seenAnyFrame = true;
                lastFrameLineIndex = i;
            }
        }

        boolean afterLastFrameBlankEmitted = !seenAnyFrame; // si pas de frame, pas de blanc spécial
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String cur = raw; // ligne telle quelle (sans trim global)

            boolean isEmpty = cur.trim().isEmpty();
            boolean isFrameDecl = frameLine.matcher(cur).find();
            boolean isFieldDecl = fieldLine.matcher(cur).find();


            if (seenAnyFrame && i > lastFrameLineIndex) {

                if (!afterLastFrameBlankEmitted) {

                    if (isEmpty) {

                        continue;
                    } else {

                        out.append("\n"); // <-- unique
                        afterLastFrameBlankEmitted = true;
                    }
                }

                if (isEmpty) {

                    continue;
                }
            }


            if (isEmpty) {

                String prev = lastNonEmptyLine(out);
                String next = nextNonEmpty(lines, i + 1);
                boolean prevIsField = prev != null && fieldLine.matcher(prev).find() && !frameLine.matcher(prev).find();
                boolean nextIsField = next != null && fieldLine.matcher(next).find() && !frameLine.matcher(next).find();

                if (prevIsField && nextIsField) {

                    continue;
                }
            }


            out.append(cur);
            if (i < lines.length - 1) out.append("\n");
        }


        String zoned = out.toString().replaceAll("(?m)\\n{3,}", "\n\n");
        return before + zoned + after;
    }

    /**
     * Renvoie la dernière ligne non vide déjà écrite dans le StringBuilder (ou null)
     */
    private String lastNonEmptyLine(StringBuilder sb) {
        String s = sb.toString();
        int i = s.length() - 1;

        while (i >= 0 && (s.charAt(i) == '\n' || s.charAt(i) == '\r')) i--;
        if (i < 0) return null;
        int start = s.lastIndexOf("\n", i) + 1;
        String line = s.substring(start, i + 1);
        return line.trim().isEmpty() ? null : line;
    }

    /**
     * Renvoie la prochaine ligne non vide à partir d'un index dans un tableau de lignes (ou null)
     */
    private String nextNonEmpty(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) return lines[i];
        }
        return null;
    }

    /**
     * Id logique de la frame: si ID fourni, on l'utilise; sinon hash du selector. (sans préfixe "frame_")
     */
    private String computeFrameLogicalId(FrameInfo fr) {
        String id = fr.getId();
        if (id != null && !id.isBlank()) {
            return id.trim();
        }
        String sel = String.valueOf(fr.getSelector());
        return Integer.toHexString(sel.hashCode());
    }

    /**
     * Nom de variable Java: "frame_" + logicalId (sanitizé), sans double préfixe
     */
    private String computeFrameVarName(FrameInfo fr) {
        String logicalId = computeFrameLogicalId(fr);
        String sanitized = logicalId.replaceAll("[^A-Za-z0-9_]", "_");
        return "frame_" + sanitized;
    }

    /**
     * Cherche une déclaration de champ existante par SELECTOR (texte "By.id(...)" exact) et Frame (optionnelle)
     * Renvoie {start, end, elementType, elementName} ou null si pas trouvé.
     */
    private Object[] findFieldDeclBySelector(String text, String selectorLiteral, String frameVarOrNull) {
        // pattern: private static <Type> <name> = new <Type>("...", <selector>[, frameVar]?);
        // on capture le type, le nom, le selecteur et (optionnellement) la frame
        String selEsc = java.util.regex.Pattern.quote(selectorLiteral.trim());
        String framePart = (frameVarOrNull == null || frameVarOrNull.isBlank())
                ? "(?:\\s*,\\s*\\w+)?"
                : "\\s*,\\s*" + java.util.regex.Pattern.quote(frameVarOrNull);
        String rx = "(?m)^\\s*private\\s+static\\s+(\\w+)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\s*\\([^;]*?"
                + selEsc + framePart + "\\s*\\)\\s*;\\s*$";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(rx);
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) {
            int start = m.start();
            int end = m.end();
            String type = m.group(1);
            String name = m.group(2);
            return new Object[]{start, end, type, name};
        }
        return null;
    }


    private String replaceFieldType(String text, int start, int end, String oldType, String newType) {
        String decl = text.substring(start, end);

        decl = decl.replaceFirst("\\b" + java.util.regex.Pattern.quote(oldType) + "\\b", newType);
        decl = decl.replaceFirst("new\\s+" + java.util.regex.Pattern.quote(oldType) + "\\b", "new " + newType);
        return text.substring(0, start) + decl + text.substring(end);
    }

}

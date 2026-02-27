package io.github.bhecquet.seleniumRobot.recorder;

import com.google.gson.Gson;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;


public class SeleniumServlet extends HttpServlet {

    private Project project;

    public SeleniumServlet(Project project) {
        this.project = project;
    }


    private void insertFrameContextLines(Editor editor, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        WriteCommandAction.runWriteCommandAction(project, () -> {
            var doc = editor.getDocument();
            int pos = editor.getCaretModel().getOffset();
            String block = String.join("\n", lines) + "\n";
            doc.insertString(pos, block);
            editor.getCaretModel().moveToOffset(pos + block.length());
        });
    }


    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {

        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor != null) {

            try {
                SeleniumAction action = new Gson().fromJson(request.getReader(), SeleniumAction.class);


//imports
                insertImports(editor, action);

//declaration elements
                insertElement(editor, action);


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

    /**
     * Insert element into class if it does not exist
     */

    /**
     * Insert element into class if it does not exist
     * - Insère les FrameElement en haut du corps de la classe (après '{'), sur une frontière de ligne
     * - Insère les autres éléments juste après le dernier FrameElement, sur une frontière de ligne
     * - Évite toute coupure de ligne (insertion milieu de ligne)
     */
    private String insertElement(Editor editor, SeleniumAction seleniumAction) {

        WriteCommandAction.runWriteCommandAction(project, () -> {

            var doc = editor.getDocument();
            String text = doc.getText();

            // --- 1) Position sûre: début du corps de classe (après '{' + fin de ligne)
            int classOpen = text.indexOf('{');
            if (classOpen < 0) {
                return; // fichier non conforme
            }
            int classBodyStart = indexAfterLineEnd(text, classOpen); // insertion ligne-sûre

            // --- 2) Dernière fin de déclaration de frame (fin de ligne)
            int framesBlockEnd = findAfterLastFrameDeclLineEnd(text, classBodyStart);

            // --- 3) Si l'action concerne un frame, insérer le FrameElement (en haut)
            if (seleniumAction.getFramePath() != null && !seleniumAction.getFramePath().isEmpty()) {

                FrameInfo fr = seleniumAction.getFramePath().get(seleniumAction.getFramePath().size() - 1);

                String frameVarName = buildFrameVarName(fr); // même algo que côté action, voir plus bas
                String selector = fr.getSelector();
                if (selector == null || selector.trim().isEmpty() || "null".equals(selector.trim())) {
                    selector = "By.cssSelector(\"iframe\")"; // fallback
                }

                String frameDecl =
                        "\n\tprivate static FrameElement " + frameVarName +
                                " = new FrameElement(\"" + buildFrameId(fr) + "\", " + selector + ");\n";

                // Si pas déjà présent, on l'insère au top des frames (après le dernier frame si existant)
                if (!containsExactFrameDecl(text, frameVarName)) {
                    int insertPos = (framesBlockEnd != -1) ? framesBlockEnd : classBodyStart;
                    doc.insertString(insertPos, frameDecl);

                    // MAJ du texte et recalcule framesBlockEnd après insertion
                    text = doc.getText();
                    framesBlockEnd = findAfterLastFrameDeclLineEnd(text, classBodyStart);
                }
            }

            // --- 4) Insérer l'élément "normal" (HtmlElement, TextFieldElement, ...) après les frames
            String elementCode = seleniumAction.getWebElementString();
            String elementType = seleniumAction.getElementType();
            String elementName = seleniumAction.getElementName();

            if (!containsExactElementDecl(text, elementType, elementName)) {
                int insertPos = (framesBlockEnd != -1) ? framesBlockEnd : classBodyStart;
                // si aucun frame, on ajoute un saut de ligne pour l'esthétique
                String block = (framesBlockEnd == -1 ? "\n" : "") + elementCode;
                doc.insertString(insertPos, block);
            }
        });

        return null;
    }

    /* ======== Helpers d'insertion sûrs ======== */

    /**
     * Renvoie l'index juste après la fin de ligne qui contient 'pos'
     */
    private int indexAfterLineEnd(String text, int pos) {
        int nl = text.indexOf('\n', pos);
        return nl >= 0 ? nl + 1 : pos + 1;
    }

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


    private void insertElementAction(Editor editor, SeleniumAction seleniumAction) {

        // Ignorer le bruit sur les SELECT (click/change) : on ne garde que "select"
        String elementType = seleniumAction.getElementType();
        String cmd = seleniumAction.getCommand();

        boolean isSelect = "SelectElement".equals(elementType) || "ListSelect".equals(elementType);
        if (isSelect && ("click".equals(cmd) || "change".equals(cmd))) {
            return;
        }

        // Fusionner les frappes : type / keyup / keydown / sendKeys
        boolean isTypingCmd = "type".equals(cmd) || "sendKeys".equals(cmd) || "keyup".equals(cmd) || "keydown".equals(cmd);

        // Ignore les sendKeys vides (souvent produits lors des pauses / espace / composition)
        if (isTypingCmd && (seleniumAction.getValue() == null || seleniumAction.getValue().isEmpty())) {
            return;
        }

        String code = seleniumAction.getFormattedCommand();
        String elementName = seleniumAction.getElementName();

        WriteCommandAction.runWriteCommandAction(project, () -> {
            int caretOffset = editor.getCaretModel().getCurrentCaret().getOffset();
            var doc = editor.getDocument();
            String text = doc.getText();

            // Dédupliquer clicks consécutifs sur le même élément
            if ("click".equals(cmd)) {
                String prevLine = previousNonEmptyLine(text, caretOffset);
                if (prevLine != null && prevLine.contains(elementName + ".click(")) {
                    return;
                }
            }

            // Si c'est une frappe clavier : remplacer la DERNIÈRE ligne sendKeys/setText du même élément
            if (isTypingCmd) {
                int[] lastCallRange = findLastTypingCallRange(text, caretOffset, elementName);
                if (lastCallRange != null) {
                    doc.replaceString(lastCallRange[0], lastCallRange[1], code);
                    editor.getCaretModel().getCurrentCaret().moveToOffset(lastCallRange[0] + code.length());
                    return;
                }
            }

            // 5) Sinon insertion normale
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
        String pattern1 = elementName + ".sendKeys(";
        String pattern2 = elementName + ".setText(";

        int start = Math.min(caretOffset, text.length());
        int i1 = text.lastIndexOf(pattern1, start);
        int i2 = text.lastIndexOf(pattern2, start);
        int idx = Math.max(i1, i2);

        if (idx < 0) return null;

        int lineStart = text.lastIndexOf('\n', idx) + 1;
        int lineEnd = text.indexOf('\n', idx);
        if (lineEnd < 0) lineEnd = text.length();

        // inclure le \n si possible, pour remplacer proprement toute la ligne
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


    /**
     * Insère les imports nécessaires (By, Keys, HtmlElement, etc.) en tête du fichier.
     */
    private void insertImports(Editor editor, SeleniumAction seleniumAction) {

        WriteCommandAction.runWriteCommandAction(project, () -> {
            var doc = editor.getDocument();
            String content = doc.getText();

            // 1) ✅ Normalise : "package ...;import ..." => "package ...;\nimport ..."
            content = content.replaceFirst("(?m)^(\\s*package\\s+[^;]+;)(?=\\s*import\\s)", "$1\n");

            // 2) ✅ Déplace les imports placés AVANT le package (restes d'une exécution précédente)
            java.util.regex.Pattern pkgPat = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+[^;]+;");
            java.util.regex.Matcher pkgM = pkgPat.matcher(content);

            if (pkgM.find()) {
                int pkgStart = pkgM.start();
                if (pkgStart > 0) {
                    String beforePkg = content.substring(0, pkgStart);

                    java.util.regex.Pattern impPat = java.util.regex.Pattern.compile("(?m)^\\s*import\\s+[^;]+;\\s*$");
                    java.util.regex.Matcher impM = impPat.matcher(beforePkg);

                    java.util.List<String> preImports = new java.util.ArrayList<>();
                    while (impM.find()) {
                        preImports.add(impM.group().trim());
                    }

                    if (!preImports.isEmpty()) {
                        // retire ces imports du bloc avant package
                        String beforeClean = beforePkg.replaceAll("(?m)^\\s*import\\s+[^;]+;\\s*\\R?", "");
                        content = beforeClean + content.substring(pkgStart);

                        // recalcul end du package après nettoyage
                        pkgM = pkgPat.matcher(content);
                        pkgM.find();
                        int pkgEnd = pkgM.end();


                        String movedBlock = "\n" + String.join("\n", preImports) + "\n";
                        content = content.substring(0, pkgEnd) + movedBlock + content.substring(pkgEnd);
                    }
                }
            }

            // applique la normalisation
            doc.setText(content);

            // 3) ✅ Calcule où insérer : après le dernier import existant (sinon après package)
            String updated = doc.getText();
            java.util.regex.Matcher pkg2 = pkgPat.matcher(updated);
            int basePos = 0;
            if (pkg2.find()) basePos = pkg2.end();

            java.util.regex.Pattern impPat2 = java.util.regex.Pattern.compile("(?m)^\\s*import\\s+[^;]+;\\s*$");
            java.util.regex.Matcher allImp = impPat2.matcher(updated);

            int lastImportEnd = -1;
            while (allImp.find()) {
                if (allImp.start() >= basePos) lastImportEnd = allImp.end();
            }
            int insertPos = (lastImportEnd != -1) ? lastImportEnd : basePos;

            // 4) ✅ Ajoute uniquement les imports manquants
            java.util.List<SeleniumAction> onlyThisAction = java.util.Collections.singletonList(seleniumAction);
            java.util.Set<String> requiredImports = ImportBuilder.computeImports(onlyThisAction);

            java.util.List<String> missingImports = new java.util.ArrayList<>();
            for (String imp : requiredImports) {
                if (!updated.contains(imp)) {
                    missingImports.add(imp);
                }
            }
            if (missingImports.isEmpty()) {
                return;
            }

            String block = "\n" + String.join("\n", missingImports) + "\n";
            doc.insertString(insertPos, block + "\n");
        });
    }


    /**
     * Retourne la position de l'accolade ouvrante de la classe (après la signature 'class ... {')
     */
    private int findClassOpenBracePos(String text) {
        // naïf mais efficace : on cherche la première '{' après "class "
        int classIdx = text.indexOf("class ");
        if (classIdx < 0) {
            // fallback: première '{' tout court
            int brace = text.indexOf('{');
            return brace >= 0 ? brace + 1 : 0;
        }
        int brace = text.indexOf('{', classIdx);
        return brace >= 0 ? brace + 1 : Math.max(0, text.indexOf('{') + 1);
    }

    /**
     * Retourne la fin du dernier 'private static FrameElement ...;' trouvé
     * (ou -1 s'il n'y en a pas)
     */
    private int findLastFrameDeclEnd(String text, int afterPos) {
        java.util.regex.Pattern framePat = java.util.regex.Pattern.compile(
                "(?m)^\\s*private\\s+static\\s+FrameElement\\s+\\w+\\s*=\\s*new\\s+FrameElement\\s*\\([^;]*\\);\\s*$"
        );
        java.util.regex.Matcher m = framePat.matcher(text);
        int lastEnd = -1;
        while (m.find()) {
            if (m.start() >= afterPos) {
                lastEnd = m.end();
            }
        }
        return lastEnd;
    }

    /**
     * Garantit qu'une déclaration de FrameElement est située dans la "zone frame"
     * (juste après l'accolade ouvrante), en tête des champs.
     * Si déjà présente, ne fait rien.
     */
    private void ensureFrameDeclAtTop(Editor editor, String frameVarName, String frameDecl) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            var doc = editor.getDocument();
            String text = doc.getText();

            // si déjà présent, on ne réinsère pas
            if (text.contains("private static FrameElement " + frameVarName + " ")) {
                return;
            }

            // base = après l'accolade ouvrante
            int classOpenPos = findClassOpenBracePos(text);

            // fin du dernier frame existant
            int lastFrameEnd = findLastFrameDeclEnd(text, classOpenPos);

            int insertPos = (lastFrameEnd != -1) ? lastFrameEnd : classOpenPos;

            // si on insère juste après '{', ajoutons un saut de ligne de confort
            String block = ((lastFrameEnd == -1) ? "\n" : "") + frameDecl;
            doc.insertString(insertPos, block);
        });
    }

}

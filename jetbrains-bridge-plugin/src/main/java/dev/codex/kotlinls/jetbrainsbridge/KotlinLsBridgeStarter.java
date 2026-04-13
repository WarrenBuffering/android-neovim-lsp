package dev.codex.kotlinls.jetbrainsbridge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarterBase;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiDocCommentOwner;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor;
import org.jetbrains.kotlin.idea.decompiler.navigation.SourceNavigationHelper;
import org.jetbrains.kotlin.idea.references.KtReference;
import org.jetbrains.kotlin.idea.references.ReferenceUtilKt;
import org.jetbrains.kotlin.psi.KtCallableDeclaration;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration;
import org.jetbrains.kotlin.psi.KtDeclaration;
import org.jetbrains.kotlin.psi.KtElement;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtNamedDeclaration;
import org.jetbrains.kotlin.psi.KtReferenceExpression;
import org.jetbrains.kotlin.references.fe10.util.DescriptorToSourceUtilsIde;
import kotlin.coroutines.Continuation;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class KotlinLsBridgeStarter extends ApplicationStarterBase {
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, Project> openProjects = new ConcurrentHashMap<>();

    public KotlinLsBridgeStarter() {
        super(1, 2);
    }

    @Override
    public @NotNull String getCommandName() {
        return "kotlinls-bridge";
    }

    @Override
    public @NotNull String getUsageMessage() {
        return "kotlinls-bridge <port>";
    }

    @Override
    public boolean isHeadless() {
        return true;
    }

    @Override
    public void premain(@NotNull List<String> args) {
        System.setProperty("idea.trust.all.projects", "true");
    }

    @Override
    protected Object executeCommand(
        @NotNull List<String> args,
        String currentDirectory,
        @NotNull Continuation<? super com.intellij.ide.CliResult> continuation
    ) {
        int port = Integer.parseInt(args.get(args.size() - 1));
        try (
            Socket socket = new Socket("127.0.0.1", port);
            BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8))
        ) {
            writeResponse(writer, new BridgeResponse(0L, true, "ready", List.of(), List.of(), null, null, null));
            String line;
            while ((line = reader.readLine()) != null) {
                BridgeRequest request = mapper.readValue(line, BridgeRequest.class);
                BridgeResponse response;
                try {
                    if ("complete".equals(request.method())) {
                        response = handleCompletion(request);
                    } else if ("hover".equals(request.method())) {
                        response = handleHover(request);
                    } else if ("definition".equals(request.method())) {
                        response = handleDefinition(request);
                    } else if ("format".equals(request.method())) {
                        response = handleFormat(request);
                    } else if ("sync".equals(request.method())) {
                        response = handleSync(request);
                    } else if ("prime".equals(request.method())) {
                        response = handleSync(request);
                    } else if ("ping".equals(request.method())) {
                        response = new BridgeResponse(request.id(), true, "pong", List.of(), List.of(), null, null, null);
                    } else {
                        response = new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Unknown method: " + request.method());
                    }
                } catch (Throwable t) {
                    response = new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, stackTrace(t));
                }
                writeResponse(writer, response);
            }
        } catch (Throwable ignored) {
        } finally {
            openProjects.values().forEach(project -> {
                if (project != null && !project.isDisposed()) {
                    ProjectUtil.closeAndDispose(project);
                }
            });
            Application application = ApplicationManager.getApplication();
            if (application != null) {
                application.invokeLater(application::exit, ModalityState.nonModal());
            }
        }
        return com.intellij.ide.CliResult.OK;
    }

    private BridgeResponse handleCompletion(BridgeRequest request) {
        CompletionPayload payload = request.payload();
        if (payload == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Missing completion payload");
        }
        Project project = openProject(payload.projectRoot());
        DocumentHandle handle = loadDocumentHandle(payload.filePath());
        if (handle == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Unable to open file: " + payload.filePath());
        }
        String currentText = payload.text() == null ? handle.document().getText() : payload.text();
        replaceDocumentText(project, handle.document(), currentText);
        DumbService.getInstance(project).waitForSmartMode();
        replaceDocumentText(project, handle.document(), currentText);
        List<BridgeCompletionItem> smartItems = collectCompletionItems(project, handle.virtualFile(), handle.document(), payload.offset(), CompletionType.SMART, payload.limit(), 1);
        replaceDocumentText(project, handle.document(), currentText);
        List<BridgeCompletionItem> basicItems = collectCompletionItems(project, handle.virtualFile(), handle.document(), payload.offset(), CompletionType.BASIC, payload.limit(), 1);
        List<BridgeCompletionItem> expandedBasicItems = List.of();
        if (isMemberAccessContext(currentText, payload.offset())) {
            replaceDocumentText(project, handle.document(), currentText);
            expandedBasicItems = collectCompletionItems(project, handle.virtualFile(), handle.document(), payload.offset(), CompletionType.BASIC, payload.limit(), 2);
        }
        List<BridgeCompletionItem> merged = mergeCompletions(payload.limit(), smartItems, basicItems, expandedBasicItems);
        return new BridgeResponse(request.id(), true, null, merged, List.of(), null, null, null);
    }

    private BridgeResponse handleSync(BridgeRequest request) {
        CompletionPayload payload = request.payload();
        if (payload == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Missing sync payload");
        }
        Project project = openProject(payload.projectRoot());
        if (payload.filePath() == null || payload.filePath().isBlank()) {
            return new BridgeResponse(request.id(), true, "synced", List.of(), List.of(), null, null, null);
        }
        DocumentHandle handle = loadDocumentHandle(payload.filePath());
        if (handle == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Unable to open file: " + payload.filePath());
        }
        replaceDocumentText(project, handle.document(), payload.text() == null ? handle.document().getText() : payload.text());
        return new BridgeResponse(request.id(), true, "synced", List.of(), List.of(), null, null, null);
    }

    private BridgeResponse handleHover(BridgeRequest request) {
        CompletionPayload payload = request.payload();
        if (payload == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Missing hover payload");
        }
        Project project = openProject(payload.projectRoot());
        DocumentHandle handle = loadDocumentHandle(payload.filePath());
        if (handle == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Unable to open file: " + payload.filePath());
        }
        String currentText = payload.text() == null ? handle.document().getText() : payload.text();
        replaceDocumentText(project, handle.document(), currentText);
        DumbService.getInstance(project).waitForSmartMode();
        String hoverMarkdown = renderHoverMarkdown(project, handle.virtualFile(), handle.document(), payload.offset());
        return new BridgeResponse(request.id(), true, hoverMarkdown == null ? "empty" : "hover", List.of(), List.of(), hoverMarkdown, null, null);
    }

    private BridgeResponse handleDefinition(BridgeRequest request) {
        CompletionPayload payload = request.payload();
        if (payload == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Missing definition payload");
        }
        Project project = openProject(payload.projectRoot());
        DocumentHandle handle = loadDocumentHandle(payload.filePath());
        if (handle == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Unable to open file: " + payload.filePath());
        }
        String currentText = payload.text() == null ? handle.document().getText() : payload.text();
        replaceDocumentText(project, handle.document(), currentText);
        DumbService.getInstance(project).waitForSmartMode();
        List<BridgeLocation> locations = resolveDefinitionLocations(project, handle.virtualFile(), handle.document(), payload.offset());
        return new BridgeResponse(request.id(), true, locations.isEmpty() ? "empty" : "definition", List.of(), locations, null, null, null);
    }

    private BridgeResponse handleFormat(BridgeRequest request) {
        CompletionPayload payload = request.payload();
        if (payload == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Missing format payload");
        }
        Project project = openProject(payload.projectRoot());
        DocumentHandle handle = loadDocumentHandle(payload.filePath());
        if (handle == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Unable to open file: " + payload.filePath());
        }
        String currentText = payload.text() == null ? handle.document().getText() : payload.text();
        replaceDocumentText(project, handle.document(), currentText);
        DumbService.getInstance(project).waitForSmartMode();
        String formattedText = formatDocument(project, handle.virtualFile(), handle.document(), payload.rangeStart(), payload.rangeEnd());
        return new BridgeResponse(request.id(), true, "formatted", List.of(), List.of(), null, formattedText, null);
    }

    private Project openProject(String projectRoot) {
        return openProjects.compute(projectRoot, (key, existing) -> {
            if (existing != null && !existing.isDisposed()) {
                return existing;
            }
            Path root = Path.of(projectRoot);
            TrustedProjects.setProjectTrusted(root, true);
            Project project = ProjectUtil.openOrImport(root, OpenProjectTask.build().withForceOpenInNewFrame(false));
            if (project == null) {
                throw new IllegalStateException("Unable to open project: " + projectRoot);
            }
            TrustedProjects.setProjectTrusted(project, true);
            hideProjectFrame(project);
            return project;
        });
    }

    private void hideProjectFrame(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            var frame = WindowManagerEx.getInstanceEx().getFrame(project);
            if (frame != null) {
                frame.setVisible(false);
            }
        }, ModalityState.nonModal());
    }

    private void replaceDocumentText(Project project, Document document, String text) {
        if (text.equals(document.getText())) {
            return;
        }
        ApplicationManager.getApplication().invokeAndWait(
            () -> WriteCommandAction.runWriteCommandAction(
                project,
                () -> {
                    document.setText(text);
                    com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);
                }
            ),
            ModalityState.nonModal()
        );
    }

    private String formatDocument(Project project, VirtualFile virtualFile, Document document, int rangeStart, int rangeEnd) {
        AtomicReference<String> formattedText = new AtomicReference<>();
        ApplicationManager.getApplication().invokeAndWait(
            () -> formattedText.set(WriteCommandAction.writeCommandAction(project).compute(() -> {
                PsiDocumentManager psiDocumentManager = PsiDocumentManager.getInstance(project);
                psiDocumentManager.commitDocument(document);
                PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
                if (psiFile == null) {
                    throw new IllegalStateException("Unable to load PSI for " + virtualFile.getPath());
                }
                CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(project);
                if (rangeStart >= 0 && rangeEnd >= 0) {
                    int start = Math.max(0, Math.min(rangeStart, document.getTextLength()));
                    int end = Math.max(start, Math.min(rangeEnd, document.getTextLength()));
                    codeStyleManager.reformatText(psiFile, List.of(new TextRange(start, end)));
                } else {
                    codeStyleManager.reformat(psiFile);
                }
                psiDocumentManager.commitDocument(document);
                return document.getText();
            })),
            ModalityState.nonModal()
        );
        return formattedText.get();
    }

    private String renderHoverMarkdown(Project project, VirtualFile virtualFile, Document document, int offset) {
        List<PsiElement> targets = resolveTargets(project, virtualFile, document, offset);
        PsiElement target = targets.isEmpty() ? declarationAtOffset(project, virtualFile, offset) : targets.getFirst();
        if (target == null) {
            return null;
        }
        PsiElement navigation = navigationTarget(target);
        String signature = renderSignature(navigation);
        String docText = extractLeadingDocText(navigation);
        StringBuilder markdown = new StringBuilder();
        if (signature != null && !signature.isBlank()) {
            markdown.append("```kotlin\n").append(signature.strip()).append("\n```");
        }
        if (docText != null && !docText.isBlank()) {
            if (markdown.length() > 0) {
                markdown.append("\n\n");
            }
            markdown.append(docText.strip());
        }
        return markdown.length() == 0 ? null : markdown.toString();
    }

    private List<BridgeLocation> resolveDefinitionLocations(Project project, VirtualFile virtualFile, Document document, int offset) {
        List<PsiElement> targets = resolveTargets(project, virtualFile, document, offset);
        List<BridgeLocation> locations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PsiElement target : targets) {
            BridgeLocation location = toBridgeLocation(navigationTarget(target));
            if (location == null) {
                continue;
            }
            String key = location.uri() + ":" + location.range().start().line() + ":" + location.range().start().character();
            if (seen.add(key)) {
                locations.add(location);
            }
        }
        return locations;
    }

    private List<PsiElement> resolveTargets(Project project, VirtualFile virtualFile, Document document, int offset) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<PsiElement>>) () -> {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
            if (psiFile == null) {
                return List.of();
            }
            PsiElement leaf = psiElementAtOffset(psiFile, offset);
            if (leaf == null) {
                return List.of();
            }
            LinkedHashSet<PsiElement> resolved = new LinkedHashSet<>();
            for (PsiElement current = leaf; current != null && current != psiFile; current = current.getParent()) {
                collectKotlinResolvedTargets(project, current, resolved);
                collectResolvedTargets(current.getReference(), resolved);
                for (PsiReference reference : current.getReferences()) {
                    collectResolvedTargets(reference, resolved);
                }
                if (!resolved.isEmpty()) {
                    break;
                }
            }
            return new ArrayList<>(resolved);
        });
    }

    private PsiElement declarationAtOffset(Project project, VirtualFile virtualFile, int offset) {
        return ApplicationManager.getApplication().runReadAction((Computable<PsiElement>) () -> {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
            if (psiFile == null) {
                return null;
            }
            PsiElement leaf = psiElementAtOffset(psiFile, offset);
            for (PsiElement current = leaf; current != null && current != psiFile; current = current.getParent()) {
                if (current instanceof KtNamedDeclaration || current instanceof PsiNameIdentifierOwner || current instanceof PsiMethod || current instanceof PsiClass || current instanceof PsiField) {
                    return current;
                }
            }
            return leaf;
        });
    }

    private PsiElement psiElementAtOffset(PsiFile psiFile, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, psiFile.getTextLength()));
        PsiElement element = psiFile.findElementAt(safeOffset);
        if (element == null && safeOffset > 0) {
            element = psiFile.findElementAt(safeOffset - 1);
        }
        return element;
    }

    private void collectResolvedTargets(PsiReference reference, Set<PsiElement> resolved) {
        if (reference == null) {
            return;
        }
        if (reference instanceof PsiPolyVariantReference polyReference) {
            for (ResolveResult result : polyReference.multiResolve(false)) {
                if (result != null && result.getElement() != null) {
                    resolved.add(result.getElement());
                }
            }
            return;
        }
        PsiElement target = reference.resolve();
        if (target != null) {
            resolved.add(target);
        }
    }

    private void collectKotlinResolvedTargets(Project project, PsiElement element, Set<PsiElement> resolved) {
        for (KtElement candidate : kotlinReferenceCandidates(element)) {
            try {
                KtReference mainReference = candidate instanceof KtReferenceExpression referenceExpression
                    ? ReferenceUtilKt.getMainReference(referenceExpression)
                    : ReferenceUtilKt.getMainReference(candidate);
                collectResolvedTargets(mainReference, resolved);
                for (DeclarationDescriptor descriptor : ReferenceUtilKt.resolveMainReferenceToDescriptors(candidate)) {
                    PsiElement declaration = DescriptorToSourceUtilsIde.INSTANCE.getAnyDeclaration(project, descriptor);
                    if (declaration != null) {
                        resolved.add(declaration);
                    }
                }
            } catch (Throwable ignored) {
                // Some PSI nodes are not resolvable through Kotlin's main-reference helpers.
            }
        }
    }

    private List<KtElement> kotlinReferenceCandidates(PsiElement element) {
        List<KtElement> candidates = new ArrayList<>();
        if (element instanceof KtCallExpression callExpression && callExpression.getCalleeExpression() instanceof KtElement callee) {
            candidates.add(callee);
        }
        if (element instanceof KtReferenceExpression referenceExpression) {
            candidates.add(referenceExpression);
        } else if (element instanceof KtElement ktElement) {
            candidates.add(ktElement);
        }
        return candidates;
    }

    private PsiElement navigationTarget(PsiElement element) {
        if (element == null) {
            return null;
        }
        if (element instanceof KtDeclaration declaration) {
            KtDeclaration sourceNavigation = SourceNavigationHelper.INSTANCE.getNavigationElement(declaration);
            if (sourceNavigation != null) {
                return sourceNavigation;
            }
        }
        PsiElement navigation = element.getNavigationElement();
        return navigation != null ? navigation : element;
    }

    private BridgeLocation toBridgeLocation(PsiElement element) {
        if (element == null) {
            return null;
        }
        PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null || element.getTextRange() == null) {
            return null;
        }
        Document targetDocument = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (targetDocument == null) {
            return null;
        }
        TextRange range = preferredTextRange(element);
        int startOffset = Math.max(0, Math.min(range.getStartOffset(), targetDocument.getTextLength()));
        int endOffset = Math.max(startOffset, Math.min(range.getEndOffset(), targetDocument.getTextLength()));
        return new BridgeLocation(
            file.getVirtualFile().getUrl(),
            new BridgeRange(positionForOffset(targetDocument, startOffset), positionForOffset(targetDocument, endOffset))
        );
    }

    private TextRange preferredTextRange(PsiElement element) {
        if (element instanceof PsiNameIdentifierOwner named && named.getNameIdentifier() != null) {
            return named.getNameIdentifier().getTextRange();
        }
        if (element instanceof KtNamedDeclaration named && named.getNameIdentifier() != null) {
            return named.getNameIdentifier().getTextRange();
        }
        return element.getTextRange();
    }

    private BridgePosition positionForOffset(Document document, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, document.getTextLength()));
        int line = document.getLineNumber(safeOffset);
        int lineStart = document.getLineStartOffset(line);
        return new BridgePosition(line, safeOffset - lineStart);
    }

    private String renderSignature(PsiElement element) {
        if (element == null) {
            return null;
        }
        if (element instanceof PsiMethod method) {
            String returnType = method.getReturnType() != null ? method.getReturnType().getPresentableText() + " " : "";
            return returnType + method.getName() + method.getParameterList().getText();
        }
        if (element instanceof PsiField field) {
            return field.getType().getPresentableText() + " " + field.getName();
        }
        if (element instanceof PsiClass psiClass) {
            String kind = psiClass.isInterface() ? "interface" : (psiClass.isEnum() ? "enum class" : "class");
            return kind + " " + psiClass.getName();
        }
        String text = element.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.replace("\r", "");
        int braceIndex = text.indexOf('{');
        if (braceIndex > 0) {
            text = text.substring(0, braceIndex);
        }
        StringBuilder signature = new StringBuilder();
        int added = 0;
        for (String line : text.strip().split("\\R")) {
            String trimmed = line.stripTrailing();
            if (trimmed.isBlank()) {
                if (added > 0) {
                    break;
                }
                continue;
            }
            if (signature.length() > 0) {
                signature.append('\n');
            }
            signature.append(trimmed);
            added += 1;
            if (added >= 8) {
                break;
            }
        }
        return signature.length() == 0 ? null : signature.toString();
    }

    private String extractLeadingDocText(PsiElement element) {
        if (element instanceof PsiDocCommentOwner owner && owner.getDocComment() != null) {
            return stripDocComment(owner.getDocComment().getText());
        }
        if (element == null || element.getContainingFile() == null || element.getContainingFile().getVirtualFile() == null || element.getTextRange() == null) {
            return null;
        }
        Document document = FileDocumentManager.getInstance().getDocument(element.getContainingFile().getVirtualFile());
        if (document == null) {
            return null;
        }
        String fullText = document.getText();
        int startOffset = Math.max(0, Math.min(element.getTextRange().getStartOffset(), fullText.length()));
        String prefix = fullText.substring(0, startOffset);
        int docEnd = prefix.lastIndexOf("*/");
        if (docEnd < 0) {
            return null;
        }
        String between = prefix.substring(docEnd + 2);
        if (!between.isBlank()) {
            return null;
        }
        int docStart = prefix.lastIndexOf("/**", docEnd);
        if (docStart < 0) {
            return null;
        }
        return stripDocComment(fullText.substring(docStart, docEnd + 2));
    }

    private String stripDocComment(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw
            .replace("\r", "")
            .replaceFirst("^/\\*\\*", "")
            .replaceFirst("\\*/$", "");
        StringBuilder text = new StringBuilder();
        for (String line : normalized.split("\\R")) {
            String cleaned = line.replaceFirst("^\\s*\\*\\s?", "").stripTrailing();
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(cleaned);
        }
        String stripped = text.toString().strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private DocumentHandle loadDocumentHandle(String filePath) {
        VirtualFile virtualFile = ApplicationManager.getApplication().runReadAction(
            (Computable<VirtualFile>) () -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(filePath))
        );
        if (virtualFile == null) {
            return null;
        }
        Document document = ApplicationManager.getApplication().runReadAction(
            (Computable<Document>) () -> FileDocumentManager.getInstance().getDocument(virtualFile)
        );
        if (document == null) {
            return null;
        }
        return new DocumentHandle(virtualFile, document);
    }

    private List<BridgeCompletionItem> collectCompletionItems(
        Project project,
        VirtualFile virtualFile,
        Document document,
        int offset,
        CompletionType completionType,
        int limit,
        int invocationCount
    ) {
        Application app = ApplicationManager.getApplication();
        Editor[] editorHolder = new Editor[1];
        app.invokeAndWait(
            () -> editorHolder[0] = EditorFactory.getInstance().createEditor(document, project, virtualFile, false),
            ModalityState.nonModal()
        );
        Editor editor = editorHolder[0];
        try {
            app.invokeAndWait(() -> {
                editor.getCaretModel().moveToOffset(Math.max(0, Math.min(offset, document.getTextLength())));
                LookupManager.hideActiveLookup(project);
            }, ModalityState.nonModal());
            app.invokeAndWait(() -> {
                CodeCompletionHandlerBase handler = CodeCompletionHandlerBase.createHandler(completionType, false, false, false);
                handler.invokeCompletion(project, editor, invocationCount, false);
            }, ModalityState.nonModal());
            Lookup lookup = waitForLookup(editor);
            if (lookup == null) {
                return List.of();
            }
            List<LookupElement> lookupItems = new ArrayList<>();
            app.invokeAndWait(() -> lookupItems.addAll(lookup.getItems()), ModalityState.nonModal());
            List<BridgeCompletionItem> items = app.runReadAction((Computable<List<BridgeCompletionItem>>) () -> {
                List<BridgeCompletionItem> collected = new ArrayList<>();
                for (LookupElement element : lookupItems) {
                    collected.add(toBridgeItem(project, element, completionType == CompletionType.SMART));
                    if (collected.size() >= limit) {
                        break;
                    }
                }
                return collected;
            });
            app.invokeAndWait(() -> LookupManager.hideActiveLookup(project), ModalityState.nonModal());
            return items;
        } finally {
            app.invokeAndWait(() -> EditorFactory.getInstance().releaseEditor(editor), ModalityState.nonModal());
        }
    }

    private Lookup waitForLookup(Editor editor) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            Lookup[] holder = new Lookup[1];
            ApplicationManager.getApplication().invokeAndWait(
                () -> holder[0] = LookupManager.getActiveLookup(editor),
                ModalityState.nonModal()
            );
            Lookup lookup = holder[0];
            if (lookup != null && !lookup.getItems().isEmpty()) {
                return lookup;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private List<BridgeCompletionItem> mergeCompletions(
        int limit,
        List<BridgeCompletionItem>... completionGroups
    ) {
        Map<String, BridgeCompletionItem> merged = new LinkedHashMap<>();
        for (List<BridgeCompletionItem> group : completionGroups) {
            for (BridgeCompletionItem item : group) {
                merged.putIfAbsent(keyOf(item), item);
                if (merged.size() >= limit) {
                    return new ArrayList<>(merged.values());
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private boolean isMemberAccessContext(String text, int offset) {
        int cursor = Math.max(0, Math.min(offset, text.length()));
        while (cursor > 0 && Character.isJavaIdentifierPart(text.charAt(cursor - 1))) {
            cursor--;
        }
        return cursor > 0 && text.charAt(cursor - 1) == '.';
    }

    private String keyOf(BridgeCompletionItem item) {
        return String.join(
            "::",
            item.lookupString(),
            item.fqName() == null ? "" : item.fqName(),
            item.kind() == null ? "" : item.kind()
        );
    }

    private BridgeCompletionItem toBridgeItem(Project project, LookupElement element, boolean smart) {
        LookupElementPresentation presentation = LookupElementPresentation.renderElement(element);
        PsiElement psi = element.getPsiElement();
        if (psi == null && element.getObject() instanceof PsiElement lookupPsi) {
            psi = lookupPsi;
        }
        CompletionMetadata metadata = extractMetadata(psi);
        String label = firstNonBlank(presentation.getItemText(), element.getLookupString());
        String detail = joinNonBlank(presentation.getTailText(), presentation.getTypeText());
        Set<String> allLookupStrings = new LinkedHashSet<>(element.getAllLookupStrings());
        allLookupStrings.add(element.getLookupString());
        return new BridgeCompletionItem(
            label,
            element.getLookupString(),
            new ArrayList<>(allLookupStrings),
            detail,
            metadata.kind(),
            metadata.fqName(),
            metadata.packageName(),
            metadata.importable(),
            metadata.receiverType(),
            smart
        );
    }

    private CompletionMetadata extractMetadata(PsiElement psi) {
        if (psi == null) {
            return new CompletionMetadata(null, null, null, false, null);
        }
        if (psi instanceof KtNamedDeclaration declaration) {
            String fqName = declaration.getFqName() != null ? declaration.getFqName().asString() : null;
            String packageName = declaration.getContainingKtFile().getPackageFqName().asString();
            boolean importable = declaration.getParent() instanceof KtFile && fqName != null;
            String receiverType = declaration instanceof KtCallableDeclaration callable && callable.getReceiverTypeReference() != null
                ? callable.getReceiverTypeReference().getText()
                : null;
            String kind = declarationKind(declaration);
            return new CompletionMetadata(kind, fqName, packageName, importable, receiverType);
        }
        if (psi instanceof PsiClass psiClass) {
            String fqName = psiClass.getQualifiedName();
            String packageName = fqName == null || !fqName.contains(".") ? "" : fqName.substring(0, fqName.lastIndexOf('.'));
            return new CompletionMetadata("class", fqName, packageName, fqName != null, null);
        }
        if (psi instanceof PsiMethod method) {
            PsiClass owner = method.getContainingClass();
            String ownerName = owner != null ? owner.getQualifiedName() : null;
            String packageName = owner != null && owner.getQualifiedName() != null && owner.getQualifiedName().contains(".")
                ? owner.getQualifiedName().substring(0, owner.getQualifiedName().lastIndexOf('.'))
                : "";
            String fqName = ownerName == null ? method.getName() : ownerName + "." + method.getName();
            return new CompletionMetadata("function", fqName, packageName, false, null);
        }
        if (psi instanceof PsiField field) {
            PsiClass owner = field.getContainingClass();
            String ownerName = owner != null ? owner.getQualifiedName() : null;
            String packageName = owner != null && owner.getQualifiedName() != null && owner.getQualifiedName().contains(".")
                ? owner.getQualifiedName().substring(0, owner.getQualifiedName().lastIndexOf('.'))
                : "";
            String fqName = ownerName == null ? field.getName() : ownerName + "." + field.getName();
            return new CompletionMetadata("property", fqName, packageName, false, null);
        }
        if (psi instanceof PsiPackage psiPackage) {
            return new CompletionMetadata("package", psiPackage.getQualifiedName(), psiPackage.getQualifiedName(), true, null);
        }
        return new CompletionMetadata(null, null, null, false, null);
    }

    private String declarationKind(KtNamedDeclaration declaration) {
        if (declaration instanceof KtClassLikeDeclaration) {
            if (declaration instanceof KtClass ktClass && ktClass.isInterface()) {
                return "interface";
            }
            return "class";
        }
        if (declaration instanceof KtCallableDeclaration callable) {
            return callable instanceof org.jetbrains.kotlin.psi.KtNamedFunction ? "function" : "property";
        }
        return null;
    }

    private String joinNonBlank(String tailText, String typeText) {
        String trimmedTail = tailText == null ? "" : tailText.trim();
        String trimmedType = typeText == null ? "" : typeText.trim();
        if (!trimmedTail.isEmpty() && !trimmedType.isEmpty()) {
            return trimmedTail + " : " + trimmedType;
        }
        return firstNonBlank(trimmedTail, trimmedType);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private void writeResponse(BufferedWriter writer, BridgeResponse response) throws java.io.IOException {
        writer.write(mapper.writeValueAsString(response));
        writer.write('\n');
        writer.flush();
    }

    private String stackTrace(Throwable throwable) {
        StringWriter buffer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(buffer));
        return buffer.toString();
    }

    private record BridgeRequest(long id, String method, CompletionPayload payload) {}

    private record CompletionPayload(String projectRoot, String filePath, String text, int offset, int limit, int rangeStart, int rangeEnd) {}

    private record BridgeResponse(
        long id,
        boolean success,
        String message,
        List<BridgeCompletionItem> items,
        List<BridgeLocation> locations,
        String hoverMarkdown,
        String formattedText,
        String error
    ) {}

    private record DocumentHandle(VirtualFile virtualFile, Document document) {}

    private record BridgeLocation(String uri, BridgeRange range) {}

    private record BridgeRange(BridgePosition start, BridgePosition end) {}

    private record BridgePosition(int line, int character) {}

    private record BridgeCompletionItem(
        String label,
        String lookupString,
        List<String> allLookupStrings,
        String detail,
        String kind,
        String fqName,
        String packageName,
        boolean importable,
        String receiverType,
        boolean smart
    ) {}

    private record CompletionMetadata(
        String kind,
        String fqName,
        String packageName,
        boolean importable,
        String receiverType
    ) {}
}

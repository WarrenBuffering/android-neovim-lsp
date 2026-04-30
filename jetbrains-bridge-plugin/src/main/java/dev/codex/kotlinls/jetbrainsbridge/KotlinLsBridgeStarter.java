package dev.codex.kotlinls.jetbrainsbridge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder;
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarterBase;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiDocCommentOwner;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.analysis.api.AnalyzeKt;
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter;
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi;
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor;
import org.jetbrains.kotlin.idea.decompiler.navigation.SourceNavigationHelper;
import org.jetbrains.kotlin.idea.highlighting.visitor.KotlinDiagnosticHighlightVisitor;
import org.jetbrains.kotlin.idea.references.KtReference;
import org.jetbrains.kotlin.idea.references.ReferenceUtilKt;
import org.jetbrains.kotlin.kdoc.psi.api.KDoc;
import org.jetbrains.kotlin.kdoc.psi.impl.KDocSection;
import org.jetbrains.kotlin.kdoc.psi.impl.KDocTag;
import org.jetbrains.kotlin.psi.KtCallableDeclaration;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration;
import org.jetbrains.kotlin.psi.KtDeclaration;
import org.jetbrains.kotlin.psi.KtElement;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jetbrains.kotlin.psi.KtNamedDeclaration;
import org.jetbrains.kotlin.psi.KtPsiFactory;
import org.jetbrains.kotlin.psi.KtReferenceExpression;
import org.jetbrains.kotlin.psi.KtSimpleNameExpression;
import org.jetbrains.kotlin.references.fe10.util.DescriptorToSourceUtilsIde;
import kotlin.coroutines.Continuation;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class KotlinLsBridgeStarter extends ApplicationStarterBase {
    private static final long RICH_DIAGNOSTICS_TIMEOUT_MILLIS = Long.getLong(
        "kotlinls.bridgeDiagnostics.richTimeoutMillis",
        2_000L
    );
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, Project> openProjects = new ConcurrentHashMap<>();
    private final Map<String, AnalysisHandle> analysisHandles = new ConcurrentHashMap<>();
    private final ExecutorService diagnosticsExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "android-neovim-lsp-bridge-diagnostics");
        thread.setDaemon(true);
        return thread;
    });

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
                    } else if ("diagnostics".equals(request.method())) {
                        response = handleDiagnostics(request);
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
            diagnosticsExecutor.shutdownNow();
            openProjects.values().forEach(project -> {
                if (project != null && !project.isDisposed()) {
                    ProjectManagerEx.getInstanceEx().forceCloseProject(project, true);
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
        AnalysisHandle handle = loadAnalysisHandle(project, payload.filePath(), payload.text());
        if (handle == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Unable to open file: " + payload.filePath());
        }
        String currentText = handle.document().getText();
        replaceDocumentText(project, handle.document(), currentText);
        DumbService.getInstance(project).waitForSmartMode();
        replaceDocumentText(project, handle.document(), currentText);
        List<BridgeCompletionItem> smartItems = collectCompletionItems(project, handle.psiFile(), handle.document(), payload.offset(), CompletionType.SMART, payload.limit(), 1);
        replaceDocumentText(project, handle.document(), currentText);
        List<BridgeCompletionItem> basicItems = collectCompletionItems(project, handle.psiFile(), handle.document(), payload.offset(), CompletionType.BASIC, payload.limit(), 1);
        List<BridgeCompletionItem> expandedBasicItems = List.of();
        if (isMemberAccessContext(currentText, payload.offset())) {
            replaceDocumentText(project, handle.document(), currentText);
            expandedBasicItems = collectCompletionItems(project, handle.psiFile(), handle.document(), payload.offset(), CompletionType.BASIC, payload.limit(), 2);
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
        AnalysisHandle handle = loadAnalysisHandle(project, payload.filePath(), payload.text());
        if (handle == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Unable to open file: " + payload.filePath());
        }
        ApplicationManager.getApplication().runReadAction((Computable<Void>) () -> {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(handle.sourceVirtualFile());
            if (psiFile != null) {
                psiFile.getTextLength();
            }
            return null;
        });
        return new BridgeResponse(request.id(), true, "synced", List.of(), List.of(), null, null, null);
    }

    private BridgeResponse handleHover(BridgeRequest request) {
        CompletionPayload payload = request.payload();
        if (payload == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Missing hover payload");
        }
        Project project = openProject(payload.projectRoot());
        AnalysisHandle handle = loadAnalysisHandle(project, payload.filePath(), payload.text());
        if (handle == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Unable to open file: " + payload.filePath());
        }
        String currentText = handle.document().getText();
        replaceDocumentText(project, handle.document(), currentText);
        DumbService.getInstance(project).waitForSmartMode();
        String hoverMarkdown = renderHoverMarkdown(project, handle, payload.offset());
        return new BridgeResponse(request.id(), true, hoverMarkdown == null ? "empty" : "hover", List.of(), List.of(), hoverMarkdown, null, null);
    }

    private BridgeResponse handleDefinition(BridgeRequest request) {
        CompletionPayload payload = request.payload();
        if (payload == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Missing definition payload");
        }
        Project project = openProject(payload.projectRoot());
        AnalysisHandle handle = loadAnalysisHandle(project, payload.filePath(), payload.text());
        if (handle == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Unable to open file: " + payload.filePath());
        }
        String currentText = handle.document().getText();
        replaceDocumentText(project, handle.document(), currentText);
        DumbService.getInstance(project).waitForSmartMode();
        List<BridgeLocation> locations = resolveDefinitionLocations(project, handle, payload.offset());
        return new BridgeResponse(request.id(), true, locations.isEmpty() ? "empty" : "definition", List.of(), locations, null, null, null);
    }

    private BridgeResponse handleDiagnostics(BridgeRequest request) {
        CompletionPayload payload = request.payload();
        if (payload == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Missing diagnostics payload");
        }
        Project project = openProject(payload.projectRoot());
        AnalysisHandle handle = loadAnalysisHandle(project, payload.filePath(), payload.text());
        if (handle == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Unable to open file: " + payload.filePath());
        }
        DiagnosticCollectionResult result = collectDiagnostics(project, handle);
        return new BridgeResponse(
            request.id(),
            true,
            result.diagnostics().isEmpty() ? "empty: " + result.message() : "diagnostics: " + result.message(),
            List.of(),
            List.of(),
            null,
            null,
            result.diagnostics(),
            null
        );
    }

    private BridgeResponse handleFormat(BridgeRequest request) {
        CompletionPayload payload = request.payload();
        if (payload == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Missing format payload");
        }
        Project project = openProject(payload.projectRoot());
        if (payload.text() == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Missing format text");
        }
        Path tempFile = null;
        try {
            tempFile = createFormattingTempFile(payload.filePath(), payload.text());
            FormattingHandle handle = loadFormattingHandle(project, tempFile);
            if (handle == null) {
                return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, "Unable to create formatting document: " + payload.filePath());
            }
            // IntelliJ formatting can operate on the PSI/document model without waiting for full smart-mode indexing.
            // Blocking here makes synchronous LSP formatting requests time out on large Android projects.
            String formattedText = formatDocument(project, handle, payload.rangeStart(), payload.rangeEnd());
            return new BridgeResponse(request.id(), true, "formatted", List.of(), List.of(), null, formattedText, null);
        } catch (Throwable t) {
            return new BridgeResponse(request.id(), false, null, List.of(), List.of(), null, null, stackTrace(t));
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private Project openProject(String projectRoot) {
        return openProjects.compute(projectRoot, (key, existing) -> {
            if (existing != null && !existing.isDisposed()) {
                return existing;
            }
            Path root = Path.of(projectRoot);
            TrustedProjects.setProjectTrusted(root, true);
            Project project = ProjectManagerEx.getInstanceEx().loadProject(root);
            if (project == null) {
                throw new IllegalStateException("Unable to open project: " + projectRoot);
            }
            TrustedProjects.setProjectTrusted(project, true);
            return project;
        });
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

    private String formatDocument(Project project, FormattingHandle handle, int rangeStart, int rangeEnd) {
        AtomicReference<String> formattedText = new AtomicReference<>();
        ApplicationManager.getApplication().invokeAndWait(
            () -> formattedText.set(WriteCommandAction.writeCommandAction(project).compute(() -> {
                PsiDocumentManager psiDocumentManager = PsiDocumentManager.getInstance(project);
                psiDocumentManager.commitDocument(handle.document());
                CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(project);
                if (rangeStart >= 0 && rangeEnd >= 0) {
                    int start = Math.max(0, Math.min(rangeStart, handle.document().getTextLength()));
                    int end = Math.max(start, Math.min(rangeEnd, handle.document().getTextLength()));
                    codeStyleManager.reformatText(handle.psiFile(), List.of(new TextRange(start, end)));
                } else {
                    codeStyleManager.reformat(handle.psiFile());
                }
                psiDocumentManager.commitDocument(handle.document());
                return handle.document().getText();
            })),
            ModalityState.nonModal()
        );
        return formattedText.get();
    }

    private FormattingHandle loadFormattingHandle(Project project, Path tempFile) {
        return ApplicationManager.getApplication().runReadAction((Computable<FormattingHandle>) () -> {
            VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(tempFile);
            if (virtualFile == null) {
                return null;
            }
            PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
            if (psiFile == null) {
                return null;
            }
            Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
            if (document == null) {
                document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
            }
            if (document == null) {
                return null;
            }
            return new FormattingHandle(psiFile, document);
        });
    }

    private RealFileHandle loadRealFileHandle(Project project, String filePath, String requestedText) {
        VirtualFile virtualFile = ApplicationManager.getApplication().runReadAction(
            (Computable<VirtualFile>) () -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(filePath))
        );
        if (virtualFile == null) {
            return null;
        }
        RealFileHandle handle = ApplicationManager.getApplication().runReadAction((Computable<RealFileHandle>) () -> {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
            if (psiFile == null) {
                return null;
            }
            Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
            if (document == null) {
                document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
            }
            if (document == null) {
                return null;
            }
            return new RealFileHandle(project, virtualFile, psiFile, document);
        });
        if (handle != null && requestedText != null) {
            replaceDocumentText(project, handle.document(), requestedText);
        }
        return handle;
    }

    private DiagnosticCollectionResult collectDiagnostics(Project project, AnalysisHandle handle) {
        Map<String, BridgeDiagnostic> diagnostics = new LinkedHashMap<>();
        commitDocumentSafely(project, handle.document());
        List<String> details = new ArrayList<>();
        details.add(collectDiagnosticsSafely("psi syntax", diagnostics, () -> collectPsiSyntaxDiagnostics(handle)));
        details.add(collectDiagnosticsSafely("kotlin analysis", diagnostics, () -> collectKotlinAnalysisDiagnostics(project, handle)));
        details.add(collectDiagnosticsSafely("studio kotlin", diagnostics, () -> collectStudioKotlinDiagnostics(project, handle)));
        details.add(collectDiagnosticsSafely("rich highlights", diagnostics, () -> collectRichHighlightDiagnostics(project, handle)));
        details.add(collectDiagnosticsSafely("cached highlights", diagnostics, () -> collectCachedHighlightDiagnostics(project, handle)));
        details.add(collectDiagnosticsSafely("unused imports", diagnostics, () -> collectKotlinUnusedImportDiagnostics(handle)));
        return new DiagnosticCollectionResult(new ArrayList<>(diagnostics.values()), String.join(", ", details));
    }

    private void commitDocumentSafely(Project project, Document document) {
        try {
            ApplicationManager.getApplication().invokeAndWait(
                () -> PsiDocumentManager.getInstance(project).commitDocument(document),
                ModalityState.nonModal()
            );
        } catch (Throwable t) {
            System.err.println("[android-neovim-lsp] diagnostics document commit failed: " + t.getMessage());
            t.printStackTrace(System.err);
        }
    }

    private String collectDiagnosticsSafely(
        String source,
        Map<String, BridgeDiagnostic> diagnostics,
        DiagnosticCollector collector
    ) {
        List<BridgeDiagnostic> collected;
        try {
            collected = collector.collect();
        } catch (Throwable t) {
            System.err.println("[android-neovim-lsp] diagnostics collector failed (" + source + "): " + t.getMessage());
            t.printStackTrace(System.err);
            return source + "=failed";
        }
        int added = 0;
        for (BridgeDiagnostic diagnostic : collected) {
            try {
                if (diagnostics.putIfAbsent(diagnosticKey(diagnostic), diagnostic) == null) {
                    added += 1;
                }
            } catch (Throwable t) {
                System.err.println("[android-neovim-lsp] diagnostics entry failed (" + source + "): " + t.getMessage());
                t.printStackTrace(System.err);
            }
        }
        return source + "=" + added + "/" + collected.size();
    }

    private List<BridgeDiagnostic> collectKotlinUnusedImportDiagnostics(AnalysisHandle handle) {
        if (!(handle.psiFile() instanceof KtFile ktFile)) {
            return List.of();
        }
        return ApplicationManager.getApplication().runReadAction((Computable<List<BridgeDiagnostic>>) () -> {
            List<KtImportDirective> imports = ktFile.getImportDirectives();
            if (imports.isEmpty()) {
                return List.of();
            }
            Set<String> referencedNames = referencedNamesAfterImports(ktFile);
            List<BridgeDiagnostic> diagnostics = new ArrayList<>();
            for (KtImportDirective importDirective : imports) {
                try {
                    if (!isProbablyUnusedImport(importDirective, referencedNames)) {
                        continue;
                    }
                    BridgeDiagnostic diagnostic = toUnusedImportDiagnostic(handle.document(), importDirective);
                    if (diagnostic != null) {
                        diagnostics.add(diagnostic);
                    }
                } catch (Throwable t) {
                    System.err.println("[android-neovim-lsp] unused import diagnostic failed: " + t.getMessage());
                    t.printStackTrace(System.err);
                }
            }
            return diagnostics;
        });
    }

    private Set<String> referencedNamesAfterImports(KtFile ktFile) {
        Set<String> referencedNames = new LinkedHashSet<>();
        TextRange importsRange = ktFile.getImportList() == null ? null : ktFile.getImportList().getTextRange();
        int importsEnd = importsRange == null ? -1 : importsRange.getEndOffset();
        ktFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                TextRange range = element.getTextRange();
                if (range != null && range.getEndOffset() <= importsEnd) {
                    return;
                }
                if (element instanceof KtSimpleNameExpression simpleNameExpression) {
                    referencedNames.add(simpleNameExpression.getReferencedName());
                }
                super.visitElement(element);
            }
        });
        return referencedNames;
    }

    private boolean isProbablyUnusedImport(KtImportDirective importDirective, Set<String> referencedNames) {
        if (!importDirective.isValid() || importDirective.isAllUnder()) {
            return false;
        }
        String importedName = importedReferenceName(importDirective);
        if (importedName == null || importedName.isBlank()) {
            return false;
        }
        if (isKotlinOperatorConventionName(importedName)) {
            return false;
        }
        return !referencedNames.contains(importedName);
    }

    private String importedReferenceName(KtImportDirective importDirective) {
        String aliasName = importDirective.getAliasName();
        if (aliasName != null && !aliasName.isBlank()) {
            return aliasName;
        }
        return importDirective.getImportedFqName() == null
            ? null
            : importDirective.getImportedFqName().shortName().asString();
    }

    private boolean isKotlinOperatorConventionName(String name) {
        return switch (name) {
            case "getValue", "setValue", "provideDelegate",
                "iterator", "hasNext", "next",
                "component1", "component2", "component3", "component4", "component5",
                "compareTo", "contains", "invoke",
                "get", "set", "plus", "minus", "times", "div", "rem", "mod",
                "rangeTo", "rangeUntil", "inc", "dec", "unaryPlus", "unaryMinus", "not" -> true;
            default -> false;
        };
    }

    private BridgeDiagnostic toUnusedImportDiagnostic(Document document, KtImportDirective importDirective) {
        if (!importDirective.isValid() || importDirective.getTextRange() == null) {
            return null;
        }
        TextRange range = importDirective.getTextRange();
        int length = document.getTextLength();
        int startOffset = Math.max(0, Math.min(range.getStartOffset(), length));
        int endOffset = Math.max(startOffset, Math.min(range.getEndOffset(), length));
        if (endOffset <= startOffset && startOffset < length) {
            endOffset = startOffset + 1;
        }
        String importedName = importDirective.getImportedFqName() == null
            ? importDirective.getText().replaceFirst("^\\s*import\\s+", "").trim()
            : importDirective.getImportedFqName().asString();
        String message = importedName.isBlank() ? "Unused import" : "Unused import: " + importedName;
        return new BridgeDiagnostic(
            new BridgeRange(positionForOffset(document, startOffset), positionForOffset(document, endOffset)),
            2,
            "unused-import",
            "android-studio",
            message
        );
    }

    private List<BridgeDiagnostic> collectPsiSyntaxDiagnostics(AnalysisHandle handle) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<BridgeDiagnostic>>) () -> {
            List<BridgeDiagnostic> diagnostics = new ArrayList<>();
            handle.psiFile().accept(new PsiRecursiveElementWalkingVisitor() {
                @Override
                public void visitErrorElement(@NotNull PsiErrorElement element) {
                    try {
                        BridgeDiagnostic diagnostic = toBridgeDiagnostic(handle.document(), element);
                        if (diagnostic != null) {
                            diagnostics.add(diagnostic);
                        }
                    } catch (Throwable t) {
                        System.err.println("[android-neovim-lsp] syntax diagnostic failed: " + t.getMessage());
                        t.printStackTrace(System.err);
                    }
                    super.visitErrorElement(element);
                }
            });
            return diagnostics;
        });
    }

    private List<BridgeDiagnostic> collectKotlinAnalysisDiagnostics(Project project, AnalysisHandle handle) {
        if (DumbService.getInstance(project).isDumb() || !(handle.psiFile() instanceof KtFile ktFile)) {
            return List.of();
        }
        EmptyProgressIndicator indicator = new EmptyProgressIndicator(ModalityState.nonModal());
        Future<List<BridgeDiagnostic>> future = diagnosticsExecutor.submit(() ->
            ProgressManager.getInstance().runProcess(
                (Computable<List<BridgeDiagnostic>>) () -> ApplicationManager.getApplication().runReadAction(
                    (Computable<List<BridgeDiagnostic>>) () -> AnalyzeKt.analyze(
                        ktFile,
                        session -> {
                            Collection<KaDiagnosticWithPsi<?>> rawDiagnostics = session.collectDiagnostics(
                                ktFile,
                                KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS
                            );
                            List<BridgeDiagnostic> diagnostics = new ArrayList<>();
                            for (KaDiagnosticWithPsi<?> diagnostic : rawDiagnostics) {
                                diagnostics.addAll(toBridgeDiagnostics(handle.document(), diagnostic));
                            }
                            return diagnostics;
                        }
                    )
                ),
                indicator
            )
        );
        try {
            return future.get(RICH_DIAGNOSTICS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            indicator.cancel();
            future.cancel(true);
            System.err.println(
                "[android-neovim-lsp] kotlin analysis diagnostics timed out after "
                    + RICH_DIAGNOSTICS_TIMEOUT_MILLIS
                    + "ms"
            );
            return List.of();
        } catch (Throwable t) {
            indicator.cancel();
            future.cancel(true);
            System.err.println("[android-neovim-lsp] kotlin analysis diagnostics failed: " + t.getMessage());
            t.printStackTrace(System.err);
            return List.of();
        }
    }

    private List<BridgeDiagnostic> collectStudioKotlinDiagnostics(Project project, AnalysisHandle handle) {
        if (DumbService.getInstance(project).isDumb() || !(handle.psiFile() instanceof KtFile ktFile)) {
            return List.of();
        }
        EmptyProgressIndicator indicator = new EmptyProgressIndicator(ModalityState.nonModal());
        Future<List<BridgeDiagnostic>> future = diagnosticsExecutor.submit(() ->
            ProgressManager.getInstance().runProcess(
                (Computable<List<BridgeDiagnostic>>) () -> ApplicationManager.getApplication().runReadAction(
                    (Computable<List<BridgeDiagnostic>>) () -> {
                        KotlinDiagnosticHighlightVisitor visitor = new KotlinDiagnosticHighlightVisitor();
                        if (!visitor.suitableForFile(ktFile)) {
                            return List.of();
                        }
                        HighlightInfoHolder holder = new HighlightInfoHolder(ktFile);
                        visitor.analyze(ktFile, true, holder, () -> ktFile.accept(new PsiRecursiveElementWalkingVisitor() {
                            @Override
                            public void visitElement(@NotNull PsiElement element) {
                                visitor.visit(element);
                                super.visitElement(element);
                            }
                        }));
                        return holderToDiagnostics(handle.document(), holder);
                    }
                ),
                indicator
            )
        );
        try {
            return future.get(RICH_DIAGNOSTICS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            indicator.cancel();
            future.cancel(true);
            System.err.println(
                "[android-neovim-lsp] studio kotlin diagnostics timed out after "
                    + RICH_DIAGNOSTICS_TIMEOUT_MILLIS
                    + "ms"
            );
            return List.of();
        } catch (Throwable t) {
            indicator.cancel();
            future.cancel(true);
            System.err.println("[android-neovim-lsp] studio kotlin diagnostics failed: " + t.getMessage());
            t.printStackTrace(System.err);
            return List.of();
        }
    }

    private List<BridgeDiagnostic> collectRichHighlightDiagnostics(Project project, AnalysisHandle handle) {
        if (DumbService.getInstance(project).isDumb()) {
            return List.of();
        }
        DaemonCodeAnalyzer analyzer = DaemonCodeAnalyzer.getInstance(project);
        if (!(analyzer instanceof DaemonCodeAnalyzerImpl daemonAnalyzer)) {
            return List.of();
        }
        EmptyProgressIndicator indicator = new EmptyProgressIndicator(ModalityState.nonModal());
        Future<List<BridgeDiagnostic>> future = diagnosticsExecutor.submit(() -> {
            List<HighlightInfo> highlights = new ArrayList<>();
            ProgressManager.getInstance().runProcess(
                () -> highlights.addAll(daemonAnalyzer.runMainPasses(handle.psiFile(), handle.document(), indicator)),
                indicator
            );
            List<BridgeDiagnostic> diagnostics = new ArrayList<>();
            for (HighlightInfo highlight : highlights) {
                try {
                    BridgeDiagnostic diagnostic = toBridgeDiagnostic(handle.document(), highlight);
                    if (diagnostic != null) {
                        diagnostics.add(diagnostic);
                    }
                } catch (Throwable t) {
                    System.err.println("[android-neovim-lsp] rich highlight diagnostic failed: " + t.getMessage());
                    t.printStackTrace(System.err);
                }
            }
            return diagnostics;
        });
        try {
            return future.get(RICH_DIAGNOSTICS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            indicator.cancel();
            future.cancel(true);
            System.err.println(
                "[android-neovim-lsp] rich diagnostics timed out after " + RICH_DIAGNOSTICS_TIMEOUT_MILLIS + "ms"
            );
            return List.of();
        } catch (Throwable t) {
            indicator.cancel();
            future.cancel(true);
            System.err.println("[android-neovim-lsp] rich diagnostics failed: " + t.getMessage());
            t.printStackTrace(System.err);
            return List.of();
        }
    }

    private List<BridgeDiagnostic> holderToDiagnostics(Document document, HighlightInfoHolder holder) {
        List<HighlightInfo> highlights = new ArrayList<>();
        for (int index = 0; index < holder.size(); index += 1) {
            HighlightInfo highlight = holder.get(index);
            if (highlight != null) {
                highlights.add(highlight);
            }
        }
        return highlightsToDiagnostics(document, highlights);
    }

    private List<BridgeDiagnostic> highlightsToDiagnostics(Document document, List<HighlightInfo> highlights) {
        List<BridgeDiagnostic> diagnostics = new ArrayList<>();
        if (highlights == null) {
            return diagnostics;
        }
        for (HighlightInfo highlight : highlights) {
            try {
                BridgeDiagnostic diagnostic = toBridgeDiagnostic(document, highlight);
                if (diagnostic != null) {
                    diagnostics.add(diagnostic);
                }
            } catch (Throwable t) {
                System.err.println("[android-neovim-lsp] highlight diagnostic failed: " + t.getMessage());
                t.printStackTrace(System.err);
            }
        }
        return diagnostics;
    }

    private List<BridgeDiagnostic> toBridgeDiagnostics(Document document, KaDiagnosticWithPsi<?> diagnostic) {
        List<BridgeDiagnostic> diagnostics = new ArrayList<>();
        Collection<TextRange> ranges = diagnostic.getTextRanges();
        if (ranges.isEmpty() && diagnostic.getPsi().getTextRange() != null) {
            ranges = List.of(diagnostic.getPsi().getTextRange());
        }
        for (TextRange range : ranges) {
            BridgeDiagnostic bridgeDiagnostic = toBridgeDiagnostic(document, diagnostic, range);
            if (bridgeDiagnostic != null) {
                diagnostics.add(bridgeDiagnostic);
            }
        }
        return diagnostics;
    }

    private BridgeDiagnostic toBridgeDiagnostic(
        Document document,
        KaDiagnosticWithPsi<?> diagnostic,
        TextRange range
    ) {
        if (range == null) {
            return null;
        }
        String message = diagnostic.getDefaultMessage();
        if (message == null || message.isBlank()) {
            message = diagnostic.getFactoryName();
        }
        if (message == null || message.isBlank()) {
            return null;
        }
        int length = document.getTextLength();
        int startOffset = Math.max(0, Math.min(range.getStartOffset(), length));
        int endOffset = Math.max(startOffset, Math.min(range.getEndOffset(), length));
        if (endOffset <= startOffset && startOffset < length) {
            endOffset = startOffset + 1;
        }
        return new BridgeDiagnostic(
            new BridgeRange(positionForOffset(document, startOffset), positionForOffset(document, endOffset)),
            diagnosticSeverity(diagnostic.getSeverity()),
            diagnostic.getFactoryName(),
            "android-studio",
            message
        );
    }

    private List<BridgeDiagnostic> collectCachedHighlightDiagnostics(Project project, AnalysisHandle handle) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<BridgeDiagnostic>>) () -> {
            List<HighlightInfo> highlights = new ArrayList<>();
            collectDocumentHighlights(project, handle.document(), HighlightSeverity.INFORMATION, highlights);
            collectDocumentHighlights(project, handle.document(), HighlightSeverity.WEAK_WARNING, highlights);
            collectDocumentHighlights(project, handle.document(), HighlightSeverity.WARNING, highlights);
            collectDocumentHighlights(project, handle.document(), HighlightSeverity.ERROR, highlights);
            DaemonCodeAnalyzer analyzer = DaemonCodeAnalyzer.getInstance(project);
            if (analyzer instanceof DaemonCodeAnalyzerImpl daemonAnalyzer) {
                try {
                    daemonAnalyzer.setHighlightingEnabled(handle.psiFile(), true);
                    daemonAnalyzer.setImportHintsEnabled(handle.psiFile(), true);
                    highlights.addAll(daemonAnalyzer.getFileLevelHighlights(project, handle.psiFile()));
                } catch (Throwable ignored) {
                }
            }
            List<BridgeDiagnostic> diagnostics = new ArrayList<>();
            for (HighlightInfo highlight : highlights) {
                try {
                    BridgeDiagnostic diagnostic = toBridgeDiagnostic(handle.document(), highlight);
                    if (diagnostic != null) {
                        diagnostics.add(diagnostic);
                    }
                } catch (Throwable t) {
                    System.err.println("[android-neovim-lsp] cached highlight diagnostic failed: " + t.getMessage());
                    t.printStackTrace(System.err);
                }
            }
            return diagnostics;
        });
    }

    private void collectDocumentHighlights(
        Project project,
        Document document,
        HighlightSeverity minSeverity,
        List<HighlightInfo> target
    ) {
        try {
            List<HighlightInfo> highlights = DaemonCodeAnalyzerImpl.getHighlights(document, minSeverity, project);
            if (highlights != null) {
                target.addAll(highlights);
            }
        } catch (Throwable ignored) {
        }
    }

    private BridgeDiagnostic toBridgeDiagnostic(Document document, PsiErrorElement errorElement) {
        String message = errorElement.getErrorDescription();
        if (message == null || message.isBlank() || errorElement.getTextRange() == null) {
            return null;
        }
        int length = document.getTextLength();
        int startOffset = Math.max(0, Math.min(errorElement.getTextRange().getStartOffset(), length));
        int endOffset = Math.max(startOffset, Math.min(errorElement.getTextRange().getEndOffset(), length));
        if (endOffset <= startOffset && startOffset < length) {
            endOffset = startOffset + 1;
        }
        return new BridgeDiagnostic(
            new BridgeRange(positionForOffset(document, startOffset), positionForOffset(document, endOffset)),
            1,
            "syntax",
            "android-studio",
            message
        );
    }

    private BridgeDiagnostic toBridgeDiagnostic(Document document, HighlightInfo highlight) {
        String message = diagnosticMessage(highlight);
        int severity = diagnosticSeverity(highlight, message);
        if (severity <= 0) {
            return null;
        }
        int length = document.getTextLength();
        int startOffset = Math.max(0, Math.min(diagnosticStartOffset(highlight), length));
        int endOffset = Math.max(startOffset, Math.min(diagnosticEndOffset(highlight), length));
        if (endOffset <= startOffset && startOffset < length) {
            endOffset = startOffset + 1;
        }
        if (message.isBlank()) {
            return null;
        }
        return new BridgeDiagnostic(
            new BridgeRange(positionForOffset(document, startOffset), positionForOffset(document, endOffset)),
            severity,
            blankToNull(highlight.getInspectionToolId()),
            "android-studio",
            message
        );
    }

    private int diagnosticSeverity(KaSeverity severity) {
        if (severity == KaSeverity.ERROR) {
            return 1;
        }
        if (severity == KaSeverity.WARNING) {
            return 2;
        }
        if (severity == KaSeverity.INFO) {
            return 3;
        }
        return 4;
    }

    private int diagnosticSeverity(HighlightInfo highlight, String message) {
        String severity = highlight.getSeverity().toString().toUpperCase(Locale.ROOT);
        if (severity.contains("ERROR")) {
            return 1;
        }
        if (severity.contains("WARNING")) {
            return 2;
        }
        if (severity.contains("INFO") || severity.contains("INFORMATION")) {
            return 3;
        }
        if (highlight.isFromInspection() || !message.isBlank()) {
            return 4;
        }
        return 0;
    }

    private String diagnosticKey(BridgeDiagnostic diagnostic) {
        return diagnostic.range().start().line()
            + ":"
            + diagnostic.range().start().character()
            + ":"
            + diagnostic.range().end().line()
            + ":"
            + diagnostic.range().end().character()
            + ":"
            + diagnostic.severity()
            + ":"
            + diagnostic.message();
    }

    private int diagnosticStartOffset(HighlightInfo highlight) {
        int start = highlight.getActualStartOffset();
        return start >= 0 ? start : highlight.getStartOffset();
    }

    private int diagnosticEndOffset(HighlightInfo highlight) {
        int end = highlight.getActualEndOffset();
        return end >= 0 ? end : highlight.getEndOffset();
    }

    private String diagnosticMessage(HighlightInfo highlight) {
        String description = highlight.getDescription();
        if (description != null && !description.isBlank()) {
            return description;
        }
        String toolTip = highlight.getToolTip();
        if (toolTip != null && !toolTip.isBlank()) {
            return stripHtml(toolTip);
        }
        return highlight.getSeverity().toString();
    }

    private String stripHtml(String text) {
        return text
            .replaceAll("(?s)<[^>]+>", "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Path createFormattingTempFile(String filePath, String text) throws java.io.IOException {
        String fileName = formattingFileName(filePath);
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String extension = dotIndex >= 0 ? fileName.substring(dotIndex) : ".kt";
        String prefix = baseName.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (prefix.length() < 3) {
            prefix = "kls" + prefix;
        }
        Path parent = formattingTempParent(filePath);
        Path tempFile = parent == null ? Files.createTempFile(
            "android-neovim-lsp-format-" + prefix + "-",
            extension.isBlank() ? ".kt" : extension
        ) : Files.createTempFile(
            parent,
            prefix + ".android-neovim-lsp-format-",
            extension.isBlank() ? ".kt" : extension
        );
        Files.writeString(tempFile, text, StandardCharsets.UTF_8);
        return tempFile;
    }

    private Path formattingTempParent(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        try {
            Path parent = Path.of(filePath).getParent();
            if (parent != null && Files.isDirectory(parent) && Files.isWritable(parent)) {
                return parent;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String formattingFileName(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "Snippet.kt";
        }
        try {
            String fileName = Path.of(filePath).getFileName().toString();
            return fileName.isBlank() ? "Snippet.kt" : fileName;
        } catch (Throwable ignored) {
            return "Snippet.kt";
        }
    }

    private String renderHoverMarkdown(Project project, AnalysisHandle handle, int offset) {
        List<PsiElement> targets = resolveTargets(project, handle.psiFile(), offset);
        PsiElement target = targets.isEmpty() ? declarationAtOffset(handle.psiFile(), offset) : targets.getFirst();
        if (target == null) {
            return null;
        }
        PsiElement navigation = navigationTarget(target);
        String signature = renderSignature(navigation);
        String docText = extractLeadingDocText(navigation, handle);
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

    private List<BridgeLocation> resolveDefinitionLocations(Project project, AnalysisHandle handle, int offset) {
        List<PsiElement> targets = resolveTargets(project, handle.psiFile(), offset);
        List<BridgeLocation> locations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PsiElement target : targets) {
            BridgeLocation location = toBridgeLocation(navigationTarget(target), handle);
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

    private List<PsiElement> resolveTargets(Project project, PsiFile psiFile, int offset) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<PsiElement>>) () -> {
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

    private PsiElement declarationAtOffset(PsiFile psiFile, int offset) {
        return ApplicationManager.getApplication().runReadAction((Computable<PsiElement>) () -> {
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

    private BridgeLocation toBridgeLocation(PsiElement element, AnalysisHandle sourceHandle) {
        if (element == null) {
            return null;
        }
        PsiFile file = element.getContainingFile();
        if (file == null || element.getTextRange() == null) {
            return null;
        }
        Document targetDocument = documentForElement(file, sourceHandle);
        String targetText = targetDocument != null ? targetDocument.getText() : file.getText();
        if (targetText == null) {
            return null;
        }
        TextRange range = preferredTextRange(element);
        int textLength = targetDocument != null ? targetDocument.getTextLength() : targetText.length();
        int startOffset = Math.max(0, Math.min(range.getStartOffset(), textLength));
        int endOffset = Math.max(startOffset, Math.min(range.getEndOffset(), textLength));
        String uri = navigationUri(file, targetText, sourceHandle);
        if (uri == null) {
            return null;
        }
        BridgePosition start = targetDocument != null
            ? positionForOffset(targetDocument, startOffset)
            : positionForOffset(targetText, startOffset);
        BridgePosition end = targetDocument != null
            ? positionForOffset(targetDocument, endOffset)
            : positionForOffset(targetText, endOffset);
        return new BridgeLocation(
            uri,
            new BridgeRange(start, end)
        );
    }

    private String navigationUri(PsiFile file, String targetText, AnalysisHandle sourceHandle) {
        if (sourceHandle != null && file == sourceHandle.psiFile()) {
            return sourceHandle.sourceVirtualFile().getUrl();
        }
        VirtualFile virtualFile = file.getVirtualFile();
        String url = virtualFile != null ? virtualFile.getUrl() : null;
        if (url != null && url.startsWith("file://") && isSourceNavigationUrl(url)) {
            return url;
        }
        return materializeNavigationTarget(file, virtualFile, targetText);
    }

    private boolean isSourceNavigationUrl(String url) {
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        return lowerUrl.endsWith(".kt") || lowerUrl.endsWith(".kts") || lowerUrl.endsWith(".java");
    }

    private String materializeNavigationTarget(PsiFile file, VirtualFile virtualFile, String text) {
        if (text == null) return null;
        String url = virtualFile != null ? virtualFile.getUrl() : null;
        String identity = url != null && !url.isBlank() ? url : file.getName() + ":" + Integer.toUnsignedString(text.hashCode(), 16);
        try {
            String cacheKey = Integer.toUnsignedString(identity.hashCode(), 16);
            Path targetDir = Path.of(System.getProperty("java.io.tmpdir"), "android-neovim-lsp-navigation", cacheKey);
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(navigationFileName(file, virtualFile, text));
            Files.writeString(target, text, StandardCharsets.UTF_8);
            return target.toUri().toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String navigationFileName(PsiFile file, VirtualFile virtualFile, String text) {
        String name = virtualFile != null ? virtualFile.getName() : null;
        if (name == null || name.isBlank()) {
            name = file.getName();
        }
        if (name == null || name.isBlank()) {
            name = "Definition.kt";
        }
        if (name.endsWith(".class")) {
            String baseName = name.substring(0, name.length() - ".class".length());
            String languageId = file.getLanguage().getID().toLowerCase(Locale.ROOT);
            if (languageId.contains("kotlin") || text.contains("fun ") || text.contains("val ")) {
                name = baseName + ".kt";
            } else if (languageId.contains("java") || text.contains(" class ") || text.contains(" interface ")) {
                name = baseName + ".java";
            }
        }
        name = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (name.isBlank()) {
            return "Definition.kt";
        }
        return name;
    }

    private Document documentForElement(PsiFile file, AnalysisHandle sourceHandle) {
        if (sourceHandle != null && file == sourceHandle.psiFile()) {
            return sourceHandle.document();
        }
        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null) {
            return null;
        }
        Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
        if (document != null) {
            return document;
        }
        return PsiDocumentManager.getInstance(sourceHandle.project()).getDocument(file);
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

    private BridgePosition positionForOffset(String text, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, text.length()));
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < safeOffset; i += 1) {
            if (text.charAt(i) == '\n') {
                line += 1;
                lineStart = i + 1;
            }
        }
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

    private String extractLeadingDocText(PsiElement element, AnalysisHandle sourceHandle) {
        if (element instanceof PsiDocCommentOwner owner && owner.getDocComment() != null) {
            PsiComment comment = owner.getDocComment();
            if (comment instanceof KDoc kDoc) {
                return renderKDocMarkdown(kDoc);
            }
            return stripDocComment(comment.getText());
        }
        if (element == null || element.getContainingFile() == null || element.getTextRange() == null) {
            return null;
        }
        Document document = documentForElement(element.getContainingFile(), sourceHandle);
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
        PsiComment comment = element.getContainingFile().findElementAt(docStart) instanceof PsiComment psiComment ? psiComment : null;
        if (comment instanceof KDoc kDoc) {
            return renderKDocMarkdown(kDoc);
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

    private String renderKDocMarkdown(KDoc kDoc) {
        if (kDoc == null) {
            return null;
        }
        KDocSection defaultSection = kDoc.getDefaultSection();
        List<String> sections = new ArrayList<>();
        String description = sanitizeKDocText(defaultSection.getContent());
        if (!description.isBlank()) {
            sections.add(description);
        }
        addKDocCodeBlockSection(sections, "Parameters", defaultSection.findTagsByName("param"));
        addKDocCodeBlockSection(sections, "Receiver", defaultSection.findTagsByName("receiver"));
        addKDocCodeBlockSection(sections, "Properties", defaultSection.findTagsByName("property"));
        addKDocCodeBlockSection(sections, "Constructor", defaultSection.findTagsByName("constructor"));
        addKDocSingleCodeBlockSection(sections, "Returns", defaultSection.findTagByName("return"));
        addKDocListSection(sections, "Throws", concatTags(defaultSection.findTagsByName("throws"), defaultSection.findTagsByName("exception")));
        addKDocListSection(sections, "See Also", defaultSection.findTagsByName("see"));
        addKDocListSection(sections, "Samples", defaultSection.findTagsByName("sample"));
        addKDocListSection(sections, "Authors", defaultSection.findTagsByName("author"));
        addKDocSingleSection(sections, "Since", defaultSection.findTagByName("since"));
        String markdown = String.join("\n\n", sections).strip();
        return markdown.isBlank() ? null : markdown;
    }

    private void addKDocCodeBlockSection(List<String> sections, String title, List<KDocTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        List<String> rendered = new ArrayList<>();
        for (KDocTag tag : tags) {
            String line = renderKDocSignatureLine(tag);
            if (!line.isBlank()) {
                rendered.add(line);
            }
        }
        if (rendered.isEmpty()) {
            return;
        }
        sections.add("### " + title + "\n```kotlin\n" + String.join("\n", rendered) + "\n```");
    }

    private void addKDocSingleCodeBlockSection(List<String> sections, String title, KDocTag tag) {
        String content = sanitizeKDocText(tag == null ? null : tag.getContent());
        if (content.isBlank()) {
            return;
        }
        sections.add("### " + title + "\n```kotlin\n" + content + "\n```");
    }

    private void addKDocListSection(List<String> sections, String title, List<KDocTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        List<String> rendered = new ArrayList<>();
        for (KDocTag tag : tags) {
            String line = renderKDocListLine(tag);
            if (!line.isBlank()) {
                rendered.add(line);
            }
        }
        if (rendered.isEmpty()) {
            return;
        }
        sections.add("### " + title + "\n" + String.join("\n", rendered));
    }

    private void addKDocSingleSection(List<String> sections, String title, KDocTag tag) {
        String content = sanitizeKDocText(tag == null ? null : tag.getContent());
        if (content.isBlank()) {
            return;
        }
        sections.add("### " + title + "\n" + content);
    }

    private String renderKDocSignatureLine(KDocTag tag) {
        String subject = tag.getSubjectName() == null ? "" : tag.getSubjectName().trim();
        String content = sanitizeKDocText(tag.getContent());
        if (!subject.isBlank() && !content.isBlank()) {
            return subject + ": " + content;
        }
        if (!subject.isBlank()) {
            return subject;
        }
        return content;
    }

    private String renderKDocListLine(KDocTag tag) {
        String subject = tag.getSubjectName() == null ? "" : tag.getSubjectName().trim();
        String content = sanitizeKDocText(tag.getContent());
        if (!subject.isBlank() && !content.isBlank()) {
            return "- `" + subject + "`: " + content;
        }
        if (!subject.isBlank()) {
            return "- `" + subject + "`";
        }
        if (!content.isBlank()) {
            return "- " + content;
        }
        return "";
    }

    private String sanitizeKDocText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n");
        List<String> lines = new ArrayList<>();
        for (String line : normalized.split("\\R")) {
            lines.add(line.stripTrailing());
        }
        String joined = String.join("\n", lines).strip();
        if (joined.isBlank()) {
            return "";
        }
        return joined
            .replaceAll("\\[(.+?)]\\[(.+?)]", "`$1`")
            .replaceAll("\\[(.+?)](?!\\()", "`$1`");
    }

    private List<KDocTag> concatTags(List<KDocTag> first, List<KDocTag> second) {
        List<KDocTag> all = new ArrayList<>();
        if (first != null) {
            all.addAll(first);
        }
        if (second != null) {
            all.addAll(second);
        }
        return all;
    }

    private AnalysisHandle loadAnalysisHandle(Project project, String filePath, String requestedText) {
        VirtualFile sourceVirtualFile = ApplicationManager.getApplication().runReadAction(
            (Computable<VirtualFile>) () -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(filePath))
        );
        if (sourceVirtualFile == null) {
            System.err.println("[android-neovim-lsp] analysis handle failed: virtual file not found for " + filePath);
            return null;
        }
        AnalysisHandle handle = analysisHandles.get(filePath);
        if (handle == null || handle.project() != project || handle.project().isDisposed() || !handle.psiFile().isValid()) {
            String initialText = requestedText != null ? requestedText : loadSourceText(sourceVirtualFile);
            handle = createAnalysisHandle(project, sourceVirtualFile, initialText);
            if (handle == null) {
                System.err.println("[android-neovim-lsp] analysis handle failed: unable to create PSI/document for " + filePath);
                return null;
            }
            analysisHandles.put(filePath, handle);
        } else if (requestedText != null) {
            replaceDocumentText(project, handle.document(), requestedText);
        } else {
            replaceDocumentText(project, handle.document(), loadSourceText(sourceVirtualFile));
        }
        return handle;
    }

    private AnalysisHandle createAnalysisHandle(Project project, VirtualFile sourceVirtualFile, String text) {
        return ApplicationManager.getApplication().runReadAction((Computable<AnalysisHandle>) () -> {
            PsiFile sourcePsiFile = PsiManager.getInstance(project).findFile(sourceVirtualFile);
            if (sourcePsiFile == null) {
                System.err.println(
                    "[android-neovim-lsp] analysis handle failed: source PSI not found for "
                        + sourceVirtualFile.getPath()
                );
                return null;
            }
            PsiFile analysisPsiFile = createAnalysisPsiFile(project, sourceVirtualFile, sourcePsiFile, text);
            if (analysisPsiFile == null) {
                System.err.println(
                    "[android-neovim-lsp] analysis handle failed: analysis PSI not created for "
                        + sourceVirtualFile.getPath()
                );
                return null;
            }
            Document document = PsiDocumentManager.getInstance(project).getDocument(analysisPsiFile);
            if (document == null) {
                document = analysisPsiFile.getViewProvider().getDocument();
            }
            if (document == null) {
                System.err.println(
                    "[android-neovim-lsp] analysis handle failed: analysis document not created for "
                        + sourceVirtualFile.getPath()
                );
                return null;
            }
            return new AnalysisHandle(project, sourceVirtualFile, analysisPsiFile, document);
        });
    }

    private PsiFile createAnalysisPsiFile(Project project, VirtualFile sourceVirtualFile, PsiFile sourcePsiFile, String text) {
        if (sourcePsiFile instanceof KtFile sourceKtFile) {
            KtFile analysisFile = KtPsiFactory.contextual(sourceKtFile).createAnalyzableFile(sourceVirtualFile.getName(), text, sourceKtFile);
            analysisFile.putUserData(PsiFileFactory.ORIGINAL_FILE, sourcePsiFile);
            return analysisFile;
        }
        PsiFile analysisFile = PsiFileFactory.getInstance(project).createFileFromText(
            sourceVirtualFile.getName(),
            sourceVirtualFile.getFileType(),
            text,
            sourceVirtualFile.getModificationStamp(),
            false,
            true
        );
        analysisFile.putUserData(PsiFileFactory.ORIGINAL_FILE, sourcePsiFile);
        return analysisFile;
    }

    private String loadSourceText(VirtualFile sourceVirtualFile) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            Document document = FileDocumentManager.getInstance().getDocument(sourceVirtualFile);
            if (document != null) {
                return document.getText();
            }
            try {
                return new String(sourceVirtualFile.contentsToByteArray(), sourceVirtualFile.getCharset());
            } catch (java.io.IOException ignored) {
                return "";
            }
        });
    }

    private List<BridgeCompletionItem> collectCompletionItems(
        Project project,
        PsiFile psiFile,
        Document document,
        int offset,
        CompletionType completionType,
        int limit,
        int invocationCount
    ) {
        Application app = ApplicationManager.getApplication();
        Editor[] editorHolder = new Editor[1];
        app.invokeAndWait(
            () -> {
                VirtualFile editorVirtualFile = psiFile.getVirtualFile();
                editorHolder[0] = editorVirtualFile != null
                    ? EditorFactory.getInstance().createEditor(document, project, editorVirtualFile, false)
                    : EditorFactory.getInstance().createEditor(document, project, psiFile.getFileType(), false);
            },
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
        List<BridgeDiagnostic> diagnostics,
        String error
    ) {
        private BridgeResponse(
            long id,
            boolean success,
            String message,
            List<BridgeCompletionItem> items,
            List<BridgeLocation> locations,
            String hoverMarkdown,
            String formattedText,
            String error
        ) {
            this(id, success, message, items, locations, hoverMarkdown, formattedText, List.of(), error);
        }
    }

    private record AnalysisHandle(Project project, VirtualFile sourceVirtualFile, PsiFile psiFile, Document document) {}

    private record FormattingHandle(PsiFile psiFile, Document document) {}

    private record RealFileHandle(Project project, VirtualFile sourceVirtualFile, PsiFile psiFile, Document document) {}

    private record DiagnosticCollectionResult(List<BridgeDiagnostic> diagnostics, String message) {}

    private record BridgeDiagnostic(BridgeRange range, int severity, String code, String source, String message) {}

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

    @FunctionalInterface
    private interface DiagnosticCollector {
        List<BridgeDiagnostic> collect();
    }
}

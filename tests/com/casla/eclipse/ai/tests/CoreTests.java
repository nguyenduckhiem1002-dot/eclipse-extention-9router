package com.casla.eclipse.ai.tests;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jface.text.Document;

import com.casla.eclipse.ai.api.ConnectionConfig;
import com.casla.eclipse.ai.api.ModelInfo;
import com.casla.eclipse.ai.completion.CodeContext;
import com.casla.eclipse.ai.completion.CompletionCache;
import com.casla.eclipse.ai.completion.CompletionPromptBuilder;
import com.casla.eclipse.ai.completion.CompletionSanitizer;
import com.casla.eclipse.ai.completion.ContextExtractor;
import com.casla.eclipse.ai.completion.CursorContextType;
import com.casla.eclipse.ai.completion.GhostTextController;
import com.casla.eclipse.ai.completion.RelatedFileCollector;
import com.casla.eclipse.ai.completion.abap.AbapMethodSignatureLookup;
import com.casla.eclipse.ai.completion.abap.AbapStructureHint;
import com.casla.eclipse.ai.internal.json.Json;
import com.casla.eclipse.ai.runtime.ModelResolver;

public final class CoreTests {
    private static int passed;

    public static void main(String[] args) {
        testJsonRoundTrip();
        testJsonUnicodeAndNumbers();
        testModelResolutionPrefersKnownGood();
        testModelResolutionAvoidsReviewAndEmbedding();
        testModelExclusion();
        testModelCapabilitiesAllowNullWireValues();
        testConnectionNormalization();
        testRemoteHttpRejected();
        testCompletionFenceRemoval();
        testCompletionFenceRemovalNotAtStart();
        testCompletionRejectsProseExplanation();
        testCompletionRejectsModelSelfCorrection();
        testCompletionKeepsRealCodeWithBacktickLiteral();
        testCompletionDeduplication();
        testAbapStructureHintInDefinitionSection();
        testAbapStructureHintInsideMethodImplementation();
        testAbapStructureHintBetweenMethods();
        testAbapStructureHintOutsideAnyClass();
        testModelResolverPrefersLowEffortModel();
        testCompletionCache();
        testExtractNextWordAndLine();
        testDetectCursorContext();
        testAbapCursorContext();
        testAbapStructureHintWithTrailingComments();
        testRelatedFileSkeleton();
        testAbapRelatedFileSkeleton();
        testCompletionPromptBuilderContexts();
        testSanitizerShortMatchRequiresWordBoundary();
        testSanitizerStillDedupesLongLiteralRepeat();
        testAbapMethodSignatureLookupFindsDeclaration();
        testAbapMethodSignatureLookupIgnoresMethodsInOtherClass();
        testAbapStructureHintEnclosingMethodNameOnlyInsideImplementation();
        testGhostCacheKeyDiffersByShape();
        System.out.println("Core tests passed: " + passed);
    }

    private static void testJsonRoundTrip() {
        Map<String, Object> input = Map.of(
            "model", "ag/claude-sonnet-4-6",
            "stream", true,
            "messages", List.of(Map.of("role", "user", "content", "hello"))
        );
        Map<String, Object> parsed = Json.object(Json.parse(Json.stringify(input)));
        check("ag/claude-sonnet-4-6".equals(parsed.get("model")), "JSON model round-trip");
        check(Boolean.TRUE.equals(parsed.get("stream")), "JSON boolean round-trip");
    }

    private static void testJsonUnicodeAndNumbers() {
        Map<String, Object> parsed = Json.object(Json.parse("{\"text\":\"Xin chào \\u263a\",\"n\":128}"));
        check("Xin chào ☺".equals(parsed.get("text")), "JSON unicode decoding");
        check(((Number) parsed.get("n")).longValue() == 128, "JSON number decoding");
    }

    private static void testModelResolutionPrefersKnownGood() {
        List<ModelInfo> models = List.of(
            model("ag/claude-sonnet-4-6"),
            model("cx/gpt-5.4-mini")
        );
        String resolved = new ModelResolver().resolve(models, "cx/gpt-5.4-mini", Set.of()).orElseThrow();
        check("cx/gpt-5.4-mini".equals(resolved), "Known-good model wins");
    }

    private static void testModelResolutionAvoidsReviewAndEmbedding() {
        List<ModelInfo> models = List.of(
            model("text-embedding-3-large"),
            model("cx/gpt-5.5-review"),
            model("ag/claude-sonnet-4-6")
        );
        String resolved = new ModelResolver().resolve(models, "", Set.of()).orElseThrow();
        check("ag/claude-sonnet-4-6".equals(resolved), "Resolver filters unsuitable models");
    }

    private static void testModelExclusion() {
        List<ModelInfo> models = List.of(
            model("ag/claude-sonnet-4-6"),
            model("cx/gpt-5.4-mini")
        );
        String resolved = new ModelResolver().resolve(
            models, "", Set.of("ag/claude-sonnet-4-6")
        ).orElseThrow();
        check("cx/gpt-5.4-mini".equals(resolved), "Failover excludes failed model");
    }

    private static void testModelCapabilitiesAllowNullWireValues() {
        java.util.LinkedHashMap<String, Object> capabilities = new java.util.LinkedHashMap<>();
        capabilities.put("reasoning", true);
        capabilities.put("thinkingRange", null);
        ModelInfo model = new ModelInfo("model", "owner", capabilities);
        check(model.capabilities().containsKey("thinkingRange"), "Model capabilities preserve null wire values");
    }

    private static void testConnectionNormalization() {
        ConnectionConfig connection = new ConnectionConfig(" http://localhost:20128/v1/// ", " key ");
        check("http://localhost:20128/v1".equals(connection.baseUrl()), "Base URL normalization");
        check("key".equals(connection.apiKey()), "API key whitespace normalization");
    }

    private static void testRemoteHttpRejected() {
        check(ConnectionConfig.validateBaseUrl("http://example.com/v1") != null, "Remote HTTP rejected");
        check(ConnectionConfig.validateBaseUrl("https://example.com/v1") == null, "Remote HTTPS accepted");
    }

    private static void testCompletionFenceRemoval() {
        String result = new CompletionSanitizer().sanitize("```java\nreturn value;\n```", context("", ""));
        check("return value;".equals(result), "Markdown fence removal");
    }

    private static void testCompletionFenceRemovalNotAtStart() {
        // A weak/fast model sometimes prefaces the fence with a sentence
        // instead of returning bare code as instructed.
        String result = new CompletionSanitizer().sanitize(
            "Sure, here's the code:\n```abap\nresult = 1.\n```",
            context("", "")
        );
        check("result = 1.".equals(result), "Fence stripped even when preceded by prose: was '" + result + "'");
    }

    private static void testCompletionRejectsProseExplanation() {
        // Exact reported failure: instead of code, the model wrote an
        // explanation naming the method in backticks, which got inserted
        // straight into the ABAP source as "result for `get_min`, it starts".
        String result = new CompletionSanitizer().sanitize(
            "for `get_min`, it starts by checking if the table is empty.",
            context("    result ", "")
        );
        check(result.isEmpty(), "Prose explanation is rejected instead of inserted: was '" + result + "'");
    }

    private static void testCompletionRejectsModelSelfCorrection() {
        // Second reported failure: the model started an answer, then
        // second-guessed its own instructions mid-stream instead of just
        // returning code -- "Wait, the prompt says..." leaked straight into
        // the suggestion.
        String result = new CompletionSanitizer().sanitize(
            "result = REDUCE i(\nWait, the prompt says the target expects MIN not MAX, let me reconsider.",
            context("", "")
        );
        check(result.isEmpty(), "Model self-correction text is rejected: was '" + result + "'");
    }

    private static void testCompletionKeepsRealCodeWithBacktickLiteral() {
        // ABAP does allow backtick string literals; a legitimate one-off
        // backtick in real code must not be caught by the prose guard.
        String result = new CompletionSanitizer().sanitize(
            "message = `Done`.",
            context("", "")
        );
        check("message = `Done`.".equals(result), "Legitimate backtick string literal is kept: was '" + result + "'");
    }

    private static void testCompletionDeduplication() {
        String result = new CompletionSanitizer().sanitize(
            "public void run() {\n    execute();\n}",
            context("public void run() {", "\n}")
        );
        check(!result.startsWith("public void run() {"), "Repeated prefix removal");
        check(!result.endsWith("\n}"), "Repeated suffix removal");
    }

    private static void testAbapStructureHintInDefinitionSection() {
        String source = """
            CLASS zcl_test_claude DEFINITION
              PUBLIC
              FINAL
              CREATE PUBLIC .

              PUBLIC SECTION.
                METHODS run
                  RETURNING VALUE(result) TYPE string.

                METHODS get_max
                  IMPORTING it_table      TYPE ty_numbers
                  RETURNING VALUE(result) TYPE i.
                methods get_min
                  IMPORTING it_table      TYPE ty_numbers
            <CURSOR>
              PROTECTED SECTION.
              PRIVATE SECTION.
            ENDCLASS.
            """;
        String hint = AbapStructureHint.scan(document(source), source.indexOf("<CURSOR>"));
        check(
            "Class ZCL_TEST_CLAUDE, DEFINITION, PUBLIC SECTION".equals(hint),
            "ABAP structure hint identifies DEFINITION + PUBLIC SECTION: was '" + hint + "'"
        );
    }

    private static void testAbapStructureHintInsideMethodImplementation() {
        String source = """
            CLASS zcl_test_claude IMPLEMENTATION.
              METHOD run.
                result = |Hello|.
              ENDMETHOD.

              METHOD get_max.
            <CURSOR>
              ENDMETHOD.
            ENDCLASS.
            """;
        String hint = AbapStructureHint.scan(document(source), source.indexOf("<CURSOR>"));
        check(
            "Class ZCL_TEST_CLAUDE, IMPLEMENTATION, inside METHOD GET_MAX".equals(hint),
            "ABAP structure hint identifies enclosing METHOD: was '" + hint + "'"
        );
    }

    private static void testAbapStructureHintBetweenMethods() {
        String source = """
            CLASS zcl_test_claude IMPLEMENTATION.
              METHOD run.
                result = |Hello|.
              ENDMETHOD.
            <CURSOR>
              METHOD get_max.
              ENDMETHOD.
            ENDCLASS.
            """;
        String hint = AbapStructureHint.scan(document(source), source.indexOf("<CURSOR>"));
        check(
            "Class ZCL_TEST_CLAUDE, IMPLEMENTATION, between methods".equals(hint),
            "ABAP structure hint reports between methods: was '" + hint + "'"
        );
    }

    private static void testAbapStructureHintOutsideAnyClass() {
        String source = """
            ENDCLASS.
            <CURSOR>
            CLASS zcl_other DEFINITION.
            """;
        String hint = AbapStructureHint.scan(document(source), source.indexOf("<CURSOR>"));
        check(hint.isEmpty(), "ABAP structure hint is empty outside any class: was '" + hint + "'");
    }

    private static void testModelResolverPrefersLowEffortModel() {
        java.util.LinkedHashMap<String, Object> heavy = new java.util.LinkedHashMap<>();
        heavy.put("reasoning", true);
        heavy.put("thinkingCanDisable", false);
        java.util.LinkedHashMap<String, Object> light = new java.util.LinkedHashMap<>();
        light.put("reasoning", true);
        light.put("thinkingCanDisable", true);

        List<ModelInfo> models = List.of(
            new ModelInfo("ag/claude-opus-4-6-thinking", "ag", heavy),
            new ModelInfo("ag/gemini-3.5-flash-extra-low", "ag", light)
        );
        String resolved = new ModelResolver().resolve(models, "", Set.of()).orElseThrow();
        check("ag/gemini-3.5-flash-extra-low".equals(resolved), "Resolver prefers the low-latency model");
    }

    private static void testCompletionCache() {
        CompletionCache cache = new CompletionCache(3, 5000);
        cache.put("k1", "val1");
        cache.put("k2", "val2");
        check("val1".equals(cache.get("k1")), "Cache hit k1");
        check("val2".equals(cache.get("k2")), "Cache hit k2");
        check(cache.get("k3") == null, "Cache miss k3");

        // Test capacity eviction
        cache.put("k3", "val3");
        cache.put("k4", "val4");
        check(cache.size() <= 3, "Cache bounds size");
    }

    private static void testExtractNextWordAndLine() {
        String text = "public static void main(String[] args) {\n    System.out.println();\n}";
        String word1 = GhostTextController.extractNextWord(text);
        check("public".equals(word1), "Extract next word 'public': was '" + word1 + "'");

        String remainder = text.substring(word1.length());
        String word2 = GhostTextController.extractNextWord(remainder);
        check(" static".equals(word2), "Extract next word ' static': was '" + word2 + "'");

        String line1 = GhostTextController.extractNextLine(text);
        check("public static void main(String[] args) {\n".equals(line1), "Extract next line: was '" + line1 + "'");
    }

    private static void testDetectCursorContext() {
        String code = "public class App {\n";
        check(ContextExtractor.detectCursorContext(code, code.length()) == CursorContextType.CODE, "Detect code context");

        String javadoc = "/**\n * Compute hash value <CURSOR>\n */\npublic void hash()";
        check(ContextExtractor.detectCursorContext(javadoc, javadoc.indexOf("<CURSOR>")) == CursorContextType.JAVADOC, "Detect Javadoc context");

        String lineComment = "public void run() {\n    // TODO: implement <CURSOR>\n}";
        check(ContextExtractor.detectCursorContext(lineComment, lineComment.indexOf("<CURSOR>")) == CursorContextType.LINE_COMMENT, "Detect line comment");

        String strLit = "String s = \"Hello world <CURSOR>\";";
        check(ContextExtractor.detectCursorContext(strLit, strLit.indexOf("<CURSOR>")) == CursorContextType.STRING_LITERAL, "Detect string literal");
    }

    private static void testAbapCursorContext() {
        String fullComment = "* Calculate discount factor <CURSOR>";
        check(ContextExtractor.detectCursorContext(fullComment, fullComment.indexOf("<CURSOR>"), "ABAP") == CursorContextType.LINE_COMMENT, "Detect ABAP full line comment (*)");

        String inlineComment = "DATA lv_counter TYPE i. \" loop counter <CURSOR>";
        check(ContextExtractor.detectCursorContext(inlineComment, inlineComment.indexOf("<CURSOR>"), "ABAP") == CursorContextType.LINE_COMMENT, "Detect ABAP inline comment (\")");

        String strLit = "lv_msg = 'Invalid input: <CURSOR>";
        check(ContextExtractor.detectCursorContext(strLit, strLit.indexOf("<CURSOR>"), "ABAP") == CursorContextType.STRING_LITERAL, "Detect ABAP string literal ('...')");

        String template = "lv_out = |Hello { lv_name }! <CURSOR>";
        check(ContextExtractor.detectCursorContext(template, template.indexOf("<CURSOR>"), "ABAP") == CursorContextType.STRING_LITERAL, "Detect ABAP string template (|...|)");

        String code = "DATA(lv_val) = 100.<CURSOR>";
        check(ContextExtractor.detectCursorContext(code, code.indexOf("<CURSOR>"), "ABAP") == CursorContextType.CODE, "Detect ABAP normal code");
    }

    private static void testAbapStructureHintWithTrailingComments() {
        String source = """
            CLASS zcl_test_trailing IMPLEMENTATION.
              METHOD run. " main execution entry
                result = |Done|.
              ENDMETHOD. " run

              METHOD get_data. " data query
            <CURSOR>
              ENDMETHOD. " get_data
            ENDCLASS. " zcl_test_trailing
            """;
        String hint = AbapStructureHint.scan(document(source), source.indexOf("<CURSOR>"));
        check(
            "Class ZCL_TEST_TRAILING, IMPLEMENTATION, inside METHOD GET_DATA".equals(hint),
            "ABAP structure hint recognizes METHOD and ENDMETHOD with trailing comments: was '" + hint + "'"
        );
    }

    private static void testRelatedFileSkeleton() {
        String fullClass = """
            package com.example;
            import java.util.List;
            
            /**
             * Service interface.
             */
            public interface UserService {
                User findById(Long id);
                void save(User user);
            }
            """;
        String skeleton = RelatedFileCollector.extractSkeleton(fullClass);
        check(skeleton.contains("package com.example;"), "Skeleton contains package");
        check(skeleton.contains("public interface UserService"), "Skeleton contains interface declaration");
        check(skeleton.contains("User findById(Long id);"), "Skeleton contains method signature");
        check(!skeleton.contains("Service interface"), "Skeleton strips comment bodies");
    }

    private static void testAbapRelatedFileSkeleton() {
        String fullAbap = """
            * Customer service definition
            CLASS zcl_customer_service DEFINITION PUBLIC FINAL CREATE PUBLIC.
              PUBLIC SECTION.
                INTERFACES if_t100_message.
                METHODS get_customer
                  IMPORTING
                    iv_id TYPE string
                  RETURNING
                    VALUE(rs_customer) TYPE zcustomer.
              PROTECTED SECTION.
                DATA mt_cache TYPE TABLE OF zcustomer.
              PRIVATE SECTION.
                " Internal helper
                METHODS load_from_db.
            ENDCLASS.
            """;
        String skeleton = RelatedFileCollector.extractAbapSkeleton(fullAbap);
        check(skeleton.contains("CLASS zcl_customer_service DEFINITION"), "ABAP Skeleton contains CLASS definition");
        check(skeleton.contains("PUBLIC SECTION."), "ABAP Skeleton contains PUBLIC SECTION");
        check(skeleton.contains("INTERFACES if_t100_message."), "ABAP Skeleton contains INTERFACES");
        check(skeleton.contains("METHODS get_customer"), "ABAP Skeleton contains METHODS signature");
        check(skeleton.contains("DATA mt_cache TYPE TABLE OF zcustomer."), "ABAP Skeleton contains DATA definition");
        check(!skeleton.contains("Customer service definition"), "ABAP Skeleton strips * comments");
        check(!skeleton.contains("Internal helper"), "ABAP Skeleton strips \" comments");
    }

    private static void testCompletionPromptBuilderContexts() {
        CodeContext javadocCtx = new CodeContext(
            "proj", "/App.java", "Java", "com.example", "import java.util.*;",
            "", "/**\n * Returns ", "\n */", 20, 1L, "fp",
            CursorContextType.JAVADOC,
            List.of(new RelatedFileCollector.RelatedFile("User.java", "public record User(String name) {}"))
        );
        var prompt = new CompletionPromptBuilder().build(javadocCtx);
        check(prompt.system().contains("Javadoc"), "Prompt builder tailors system prompt for Javadoc");
        check(prompt.user().contains("Related context:"), "Prompt builder includes related context");
        check(prompt.user().contains("User.java"), "Prompt builder includes related file name");
    }

    private static void testSanitizerShortMatchRequiresWordBoundary() {
        // "before" ends mid-identifier ("prev_i"); the completion starting
        // with "if" shares a coincidental one-character "i" with the tail of
        // that identifier, but "i" there isn't a token boundary -- it must
        // not be treated as an echoed prefix and chopped to "f (x > 0) {".
        String result = new CompletionSanitizer().sanitize("if (x > 0) {", context("prev_i", ""));
        check(result.startsWith("if ("), "Sanitizer does not cut mid-token: was '" + result + "'");
    }

    private static void testSanitizerStillDedupesLongLiteralRepeat() {
        // A weak model echoing back the text it was already given is the
        // original reason this dedup exists; an 8+ char echo must still be
        // stripped unconditionally regardless of the word-boundary rule
        // added for short matches.
        String result = new CompletionSanitizer().sanitize(
            "String name = customer.getCustomerName()",
            context("String name = customer.", "")
        );
        check("getCustomerName()".equals(result), "Long echoed prefix is still removed: was '" + result + "'");
    }

    private static void testAbapMethodSignatureLookupFindsDeclaration() {
        String source = """
            CLASS zcl_test_claude DEFINITION.
              PUBLIC SECTION.
                METHODS get_max
                  IMPORTING it_table      TYPE ty_numbers
                  RETURNING VALUE(result) TYPE i.
            ENDCLASS.

            CLASS zcl_test_claude IMPLEMENTATION.
              METHOD get_max.
              ENDMETHOD.
            ENDCLASS.
            """;
        String signature = AbapMethodSignatureLookup.find(source, "get_max");
        check(signature.startsWith("METHODS get_max"), "Signature lookup finds the METHODS declaration: was '" + signature + "'");
        check(signature.contains("IMPORTING it_table"), "Signature lookup keeps the full multi-line declaration");
        check(signature.endsWith("."), "Signature lookup includes the terminating period");
    }

    private static void testAbapMethodSignatureLookupIgnoresMethodsInOtherClass() {
        String source = """
            CLASS zcl_other DEFINITION.
              PUBLIC SECTION.
                METHODS run.
            ENDCLASS.
            """;
        String signature = AbapMethodSignatureLookup.find(source, "get_max");
        check(signature.isEmpty(), "Signature lookup returns empty when the method isn't declared anywhere");
    }

    private static void testAbapStructureHintEnclosingMethodNameOnlyInsideImplementation() {
        String implSource = """
            CLASS zcl_test_claude IMPLEMENTATION.
              METHOD get_max.
            <CURSOR>
              ENDMETHOD.
            ENDCLASS.
            """;
        String name = AbapStructureHint.enclosingMethodName(document(implSource), implSource.indexOf("<CURSOR>"));
        check("GET_MAX".equals(name), "Enclosing method name resolves inside IMPLEMENTATION: was '" + name + "'");

        String defSource = """
            CLASS zcl_test_claude DEFINITION.
              PUBLIC SECTION.
                METHODS get_max
            <CURSOR>
                  RETURNING VALUE(result) TYPE i.
            ENDCLASS.
            """;
        String blankInDefinition = AbapStructureHint.enclosingMethodName(document(defSource), defSource.indexOf("<CURSOR>"));
        check(blankInDefinition.isEmpty(), "Enclosing method name is blank inside DEFINITION, not just IMPLEMENTATION");
    }

    private static void testGhostCacheKeyDiffersByShape() {
        CodeContext ctx = context("before", "after");
        String blockKey = GhostTextController.cacheKey(ctx, false);
        String lineKey = GhostTextController.cacheKey(ctx, true);
        check(!blockKey.equals(lineKey), "Cache key differs between block and single-line shapes so they never cross-serve");
    }

    private static Document document(String text) {
        return new Document(text.replace("<CURSOR>", ""));
    }

    private static ModelInfo model(String id) {
        return new ModelInfo(id, "test", Map.of());
    }

    private static CodeContext context(String before, String after) {
        return new CodeContext("p", "/A.java", "Java", "", "", "", before, after, before.length(), 1L, "x");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
    }
}

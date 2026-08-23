# Kế hoạch nâng cấp AI Code Assistant cho ABAP ADT

> Bản hiệu chỉnh theo hiện trạng repo tại commit `75b42a1` (2026-08-22).
> Khác với template gốc: plan này **không giả định component chưa tồn tại** — mọi mục đều đánh dấu
> ✅ đã có / 🔶 có một phần / ❌ chưa có, và tham chiếu file thật trong `src/`.

---

## 1. Tóm tắt kiến trúc hiện tại

Một bundle duy nhất `com.casla.eclipse.ai` (Java 21, build bằng `build.ps1` — javac trực tiếp
lên `plugins/` của Eclipse, không Tycho/PDE). Các tầng:

| Tầng | Class | Ghi chú |
|---|---|---|
| Lifecycle | `AiPlugin` | `AbstractUIPlugin`, khởi động `AiRuntime` + `GhostTextController` |
| State machine | `runtime/AiRuntime` | singleton; `RuntimeSnapshot` (ConnectionStatus × ModelStatus); generation counter chống stale; **sanitize nằm trong `complete()`** nên `markKnownGood` chỉ chạy khi output dùng được |
| Model selection | `runtime/ModelResolver` | scoring ưu tiên latency (`-low`/`flash`/`mini`…, phạt `opus`/`thinking`); known-good pin +10.000; failover 1 lần |
| HTTP client | `client/OpenAiCompatibleClient` | java.net.http, SSE streaming, `reasoning_effort` + retry-bỏ-param khi 400, `stop:["\n"]` cho single-line |
| Ghost text | `completion/GhostTextController` | overlay `PaintListener` trên StyledText; Tab/Esc/Ctrl+Right/Alt+]/Ctrl+Down; typed-prefix consumption; `TicketMonitor` hủy request giữa stream; multi-line chỉ vẽ lên dòng trống, ngược lại "+N lines (Tab)" |
| Popup ADT | `abap/AiAbapProposalsProvider` | ext point `com.sap.adt.tools.abapsource.ui.clientProposalProvider` (nội bộ SAP, `resolution:=optional`); block UI ≤300ms; kết quả trễ chuyển sang ghost text |
| Popup JDT | `AiCompletionProposalComputer` | `javaCompletionProposalComputer` (public API) |
| Context ABAP | `abap/AbapContextExtractor` + `AbapStructureHint` + `AbapMethodSignatureLookup` + `CursorContextType` + `RelatedFileCollector` | structure hint (class/DEFINITION-IMPLEMENTATION/SECTION/METHOD), signature từ DEFINITION khi đang viết body, phát hiện comment `*`/`"`, literal `'…'`, template `\|…\|`; skeleton các editor đang mở (cache theo mod-stamp) |
| Prompt | `CompletionPromptBuilder` | FIM (before/`<CURSOR>`/after), role theo cursor context, khối ABAP rules (METHODS vs METHOD…) |
| Sanitizer | `CompletionSanitizer` | strip fence ở mọi vị trí; **reject prose** (word-run + discourse opener + backtick-mention); dedup prefix/suffix có word-boundary guard |
| Cache | `CompletionCache` | LRU 64 + TTL 60s, key = fingerprint + shape (`:line`/`:block`), dùng chung popup ↔ ghost |
| Prefs | `preferences/*` | Secure Storage cho API key; debounce/timeout/context budget/reasoning effort; nút **Reset** model memory |
| Test | `tests/CoreTests` (64 assert) | main-based, chạy trong build; `LiveEndpointTest` optional theo env |

Môi trường thực tế: Eclipse 2026-06 + ADT, gateway OpenAI-compatible tại `localhost:20128`
(9Router, model `ag/*`), default `ag/gemini-3.5-flash-low`.

## 2. Vấn đề & giới hạn hiện tại (đã kiểm chứng trong code)

1. **Không có tầng completion local** — mọi gợi ý đều gọi AI, kể cả `ENDIF.`/`ENDMETHOD.` là thứ
   suy ra được bằng 0ms. Đây là khoảng trống lớn nhất so với trải nghiệm Copilot.
2. **Không có symbol table** — context chỉ là cửa sổ text + structure hint + 1 signature.
   Model không biết danh sách biến local, attribute, kiểu dữ liệu → dễ bịa tên biến.
3. **Không có validation cú pháp** trước khi hiện: không kiểm tra cân bằng IF/ENDIF,
   LOOP/ENDLOOP; không phát hiện identifier lạ.
4. **Trigger engine thô**: debounce cố định 500ms; vẫn trigger khi đang xoá, khi đang gõ
   comment (CursorContextType chỉ dùng để đổi role prompt, chưa dùng để chặn trigger);
   **chưa kiểm tra xung đột với Content Assist popup của ADT** (Tab có thể bị ghost nuốt
   khi popup đang mở — VerifyKeyListener được prepend).
5. **Không có status indicator** — người dùng không biết plugin đang idle/generating/error
   (đã gây nhầm lẫn thực tế với dialog lỗi của chính SAP ADT).
6. **Chỉ 1 suggestion**, không có alternatives/cycling.
7. **Không telemetry** — không đo được latency P50/P95, acceptance rate, để tinh chỉnh.
8. **Bảo mật mức cơ bản**: chưa có secret redaction, chưa có exclude list, chưa có privacy mode.
9. **DDIC/CDS metadata = 0**: ADT không公 khai semantic API; mọi thứ hiện là text heuristics.
10. Undo/redo sau accept là 1 `document.replace` — **chưa có test xác nhận** là 1 undo unit.

## 3. Kiến trúc mục tiêu

Giữ nguyên khung hiện tại (đang chạy tốt), bổ sung 3 khối mới:

```
Keystroke ──▶ TriggerEngine (mới)          ──▶ Tier 1: AbapLocalCompleter (mới, 0ms)
                 │ debounce thích ứng            │ đóng block, keyword template
                 │ chặn comment/delete/popup     ▼ miss
                 ▼                          Tier 2: CompletionCache (✅ đã có)
             ContextEngine (nâng cấp)            ▼ miss
                 │ + AbapScopeExtractor     Tier 3: AiRuntime → OpenAiCompatibleClient (✅)
                 ▼                               ▼
             CompletionPromptBuilder (✅)   ValidationPipeline (mới)
                                                 │ sanitize (✅) + block balance + scope check
                                                 ▼
                                            GhostTextController (✅) / ADT popup (✅)
                                                 │
                                            StatsCollector (mới, local-only)
```

## 4. Luồng dữ liệu keystroke → ghost text (hiện tại + phần mới in đậm)

1. `documentChanged` → **TriggerEngine quyết định**: bỏ qua nếu (a) đang xoá, (b) cursor trong
   comment/string (dùng `CursorContextType` sẵn có), (c) Content Assist popup đang mở
   (`ISourceViewerExtension4.getContentAssistantFacade()` — JFace public API), (d) prefix vô nghĩa.
2. Debounce **thích ứng**: 150ms sau newline hoặc sau từ khóa mở block
   (`DATA`, `IF`, `LOOP`, `SELECT`, `METHOD`, `TRY`, `CASE`, `RETURN`); 400–500ms còn lại; 50ms
   sau accept (✅ đã có).
3. **Tier 1 local**: nếu vị trí khớp pattern chắc chắn (dòng trống ngay sau block mở chưa đóng →
   gợi ý `ENDIF.`/`ENDLOOP.`/…; sau `METHODS xyz` trong DEFINITION khi IMPLEMENTATION thiếu →
   khung `METHOD xyz. ENDMETHOD.`) → hiện ghost ngay, không gọi AI.
4. Cache lookup theo fingerprint+shape (✅).
5. Miss → `AbapContextExtractor` (+ **AbapScopeExtractor** mới: DATA/TYPES/CONSTANTS/
   FIELD-SYMBOLS/param từ signature + attribute của class, parse text, không cần ADT API)
   → prompt → `AiRuntime.complete()` với `TicketMonitor` (✅ hủy giữa stream).
6. Response → sanitize (✅) → **ValidationPipeline**: cân bằng block, cắt phần vượt scope,
   downrank khi gọi `me->x` không có trong attribute list.
7. Stale-guard: modificationStamp + caret + ticket (✅) → paint (✅ đã an toàn overlay).
8. **StatsCollector** ghi: requested/displayed/accepted/rejected/latency/cancelled (local file).

## 5. Context Engine — phạm vi thực tế

Template gốc yêu cầu DDIC, dependency graph, CDS association… **ADT không có public API cho
semantic model**; gói `com.sap.adt.*` ta đang dùng đã là internal (`resolution:=optional`).
Quyết định:

- **Nguồn chính (P0)**: text của chính source + các editor đang mở (✅ RelatedFileCollector).
  ✅ `AbapScopeExtractor` (text-parse, không phân biệt local/attribute — xem giới hạn ở mục 11):
  quét `NAME TYPE type` + `VALUE(name) TYPE type` toàn document + tên METHODS, đưa vào prompt
  dạng bảng `symbol: type`, đặt ngay sau method signature.
- **Ranking & budget**: giữ `contextBefore/After` theo prefs; related files ≤4×1200 chars (✅);
  scope table ≤~600 tokens; thứ tự: signature hiện tại → scope table → related skeletons.
- **DDIC/CDS metadata (P2 — spike riêng)**: khả thi duy nhất là ADT REST
  (`/sap/bc/adt/ddic/...`) qua `com.sap.adt.communication` (internal). Rủi ro cao, cần SAP
  system permission. Chỉ làm sau khi Tier 1+Scope chứng minh chưa đủ. Fallback đã có sẵn:
  không có metadata thì prompt bỏ trống mục đó (builder đã omit-empty).

## 6. Ghost text — trạng thái & việc còn lại

✅ đã đạt: overlay an toàn (không vẽ đè code), Tab/word/line-accept, typed-prefix consumption,
tự ẩn khi gõ lệch, không hiện suggestion rỗng (sanitizer), giới hạn preview qua "+N lines".

Việc còn lại:
- ✅ Chặn ghost khi Content Assist popup đang mở (`ICompletionListener` trên
  `ContentAssistantFacade`) — trước P0/S, đã xong.
- ❌ Manual trigger command (`Alt+\`) + `org.eclipse.ui.commands` để rebind được — P1, M.
- ❌ Alternatives + `Alt+]`/`Alt+[` cycling: **xung đột với Alt+] accept-word hiện tại**.
  Quyết định: giữ Alt+] = accept word (parity Copilot dùng Ctrl+Right nhưng ta hỗ trợ cả 2);
  cycling chuyển sang `Alt+PgUp/PgDn`, chỉ làm khi gateway xác nhận hỗ trợ `n>1` — P2, M.
- 🔶 Giới hạn số dòng preview: có qua cơ chế dòng-trống; thêm pref `maxSuggestionLines` — P2, S.

## 7. Prompt ABAP — giữ contract text, KHÔNG dùng JSON schema

Khác template gốc: **không** yêu cầu model trả JSON `{completion, alternatives, confidence…}`.
Lý do đã kiểm chứng bằng 2 sự cố thật: model tier nhanh còn không giữ nổi lệnh "code only"
(leak prose/`Wait, the prompt says…`); ép JSON sẽ tăng tỉ lệ hỏng và độ trễ. Contract hiện tại
= plain text + sanitizer phòng thủ (✅) là đúng cho hạ tầng này. "Confidence" thay bằng
heuristic phía client (kết quả validation + độ dài + block balance).

Bổ sung vào prompt (P1):
- `ABAP release` (pref mới, default "7.40+") + câu cấm cú pháp mới hơn release.
- Scope table từ `AbapScopeExtractor` + luật "chỉ dùng identifier có trong danh sách hoặc
  khai báo mới bằng DATA(...)".
- Phát hiện case-style (file đang dùng lowercase keyword → yêu cầu giữ style) — P1, S.

## 8. Cancellation / cache / concurrency — trạng thái

✅ `TicketMonitor` hủy SSE giữa stream; ✅ generation counter trong AiRuntime; ✅ cache chung
LRU+TTL; ✅ skeleton cache theo mod-stamp; ✅ retry-once bỏ `reasoning_effort` khi 400.
Còn lại: ❌ circuit breaker (mở sau N lỗi liên tiếp, đóng dần) + backoff khi 429 — P1, M;
❌ giới hạn concurrent request = 1 ghost + 1 popup (thực tế executor đã 1 thread ghost,
2 thread popup — chỉ cần siết popup còn 1) — P2, S.

## 9. Bảo mật & riêng tư

✅ Secure Storage cho key; ✅ HTTPS bắt buộc trừ localhost; ✅ không log prompt/source.
Bổ sung:
- ❌ **Secret redaction** trước khi gửi: regex password/token/AWS-style/`sy-uname` literal…
  thay bằng `<redacted>` — P1, S.
- ❌ Exclude list theo tên object/pattern (pref, ví dụ `Z_CONF*`) → không gửi source — P1, S.
- ❌ Privacy mode: tắt related-files, thu hẹp window — P2, S.
- Telemetry (mục 10) **local-only, không gửi đi đâu** — phù hợp bối cảnh 1 dev + gateway riêng,
  không cần backend như template gốc.

## 10. Telemetry & chất lượng — thu nhỏ thành StatsCollector local

Counter in-memory + flush định kỳ vào `.metadata/.plugins/com.casla.eclipse.ai/stats.csv`:
requested, tier1-served, cache-hit, displayed, accepted(full/word/line), dismissed, cancelled,
sanitize-rejected, error(code), latency ms (percentile tính khi đọc). Xem qua nút
"Diagnostics" trong Preferences. Không thu source. Đủ để trả lời: acceptance rate,
sanitize-reject rate theo model → chọn model bằng số liệu thay vì cảm giác.

## 11. Danh sách module tạo mới / sửa

| # | Module | Loại | P | Size |
|---|---|---|---|---|
| 1 | ✅ `completion/TriggerEngine` (tách từ GhostTextController.scheduleFetch) | mới | P0 | M |
| 2 | ✅ Chặn trigger khi comment/delete/popup mở | sửa GhostTextController | P0 | S |
| 3 | ✅ `abap/AbapLocalCompleter` (Tier 1: đóng block IF/LOOP/CASE/TRY/DO/WHILE) | mới | P0 | M |
| 4 | ✅ `abap/AbapScopeExtractor` (symbol table text-parse) | mới | P0 | L |
| 5 | ✅ `completion/ValidationPipeline` (structure cross-check + paren balance) | mới | P0 | M |
| 6 | `ui/AiStatusTrim` (status indicator: Ready/Generating/Error/RateLimited) | mới + plugin.xml | P1 | M |
| 7 | `runtime/StatsCollector` | mới | P1 | M |
| 8 | Secret redaction + exclude list | sửa OpenAiCompatibleClient + prefs | P1 | S |
| 9 | Circuit breaker + 429 backoff | sửa AiRuntime | P1 | M |
| 10 | Manual trigger command + keybinding qua `org.eclipse.ui.commands` | plugin.xml + handler | P1 | M |
| 11 | ABAP release pref + case-style detection vào prompt | sửa PromptBuilder/prefs | P1 | S |
| 12 | ✅ Debounce thích ứng theo keyword/newline | trong TriggerEngine | P1 | S |
| 13 | CDS/RAP/Unit artifact detection (theo editor site id) + rules riêng | mở rộng CursorContextType/StructureHint | P2 | L |
| 14 | Alternatives + cycling (chờ xác nhận gateway `n>1`) | GhostTextController | P2 | M |
| 15 | DDIC qua ADT REST (spike) | mới, optional bundle | P2 | XL |
| 16 | Golden dataset ABAP (≥20 case) + benchmark script | tests/resources | P1 | M |

Mỗi mục: rủi ro & test ghi ở phase tương ứng bên dưới.

## 12. Kế hoạch phase (đã re-baseline theo hiện trạng)

### Phase 0 — Baseline đo đạc (Size S) ✅ một phần
- ✅ Kiến trúc đã phân tích (tài liệu này). ✅ API risk đã phân loại (mục 5).
- ❌ Còn lại: gắn StatsCollector tối thiểu (requested/displayed/latency) TRƯỚC các phase sau
  để có số so sánh. **DoD**: stats.csv có dữ liệu sau 1 ngày dùng thật.

### Phase 1' — Vá nốt ghost text ✅ xong (#2, #12; #6/#10 vẫn P1, chưa làm)
- ✅ `getContentAssistantFacade()` trả về `ContentAssistantFacade` (không phải interface —
  đã kiểm chứng bằng `javap` trên jar thật), track qua `ICompletionListener`, fallback null-safe
  đã có sẵn (`if (assistFacade != null)`).
- ✅ **DoD đạt**: gõ trong comment (LINE_COMMENT/BLOCK_COMMENT) không bắn request; xoá liên tục
  không bắn request; popup ADT mở → ghost tự clear + cancel ticket, Tab đi vào popup.
- ❌ **Chưa làm**: test undo/redo tự động (accept → 1 Ctrl+Z hoàn tác hết) — chỉ mới đúng về
  thiết kế (1 lần `document.replace`), chưa có test xác nhận trong Eclipse thật.

### Phase 2' — ABAP Context Engine v2 ✅ xong (#4; #11 vẫn P1, chưa làm)
- ✅ **Rủi ro đã biết còn tồn tại**: `AbapScopeExtractor` cố tình KHÔNG track statement boundary
  (dùng whole-document regex scan thay vì per-line) — chọn "quét rộng, có thể lẫn" thay vì
  "bỏ sót khai báo chained" sau khi nhận ra per-line anchor sẽ bỏ sót dòng tiếp theo của
  `DATA: a,\n b.`. Đây là trade-off có chủ đích, không phải bug.
- ✅ **DoD đạt**: CoreTests có 8 case cho scope extraction + local completer liên quan; prompt
  chứa bảng symbol khi có (`AbapContextExtractor.withScope`). ❌ Chưa đo sanitize-reject rate
  thực tế (cần StatsCollector — Phase 0 còn thiếu phần đó).

### Phase 3' — Tier 1 + Validation ✅ xong (#3, #5; #9 vẫn P1, chưa làm)
- ✅ Tier 1 chỉ kích hoạt khi dòng hiện tại trống VÀ scan xác nhận block chưa đóng — dùng stack
  matching riêng (không tái dùng AbapStructureHint vì đó là cho CLASS/METHOD, khác phạm vi
  IF/LOOP/CASE/TRY/DO/WHILE).
- **DoD**: `ENDIF.` hiện <50ms không gọi mạng; completion mất cân bằng block không bao giờ
  hiển thị; stats cho thấy tier1-served > 0.

### Phase 4' — Hệ sinh thái ABAP (CDS/RAP/Unit)
Mục #13, mở rộng golden dataset. Chỉ bắt đầu khi acceptance rate class/method ổn.
- **DoD**: mở file CDS DDL → prompt nhận đúng artifact type + rules CDS; benchmark case
  CDS/RAP pass.

### Phase 5' — Hardening
Mục #7 (đầy đủ), #8, #14, #15(spike), compatibility check ADT versions.
- **DoD**: secret giả trong source không xuất hiện trong request (test bắt payload);
  circuit breaker mở sau 5 lỗi liên tiếp và tự đóng; tài liệu cài đặt/rollback.

## 13. Rủi ro kỹ thuật & fallback

| Rủi ro | Mức | Fallback |
|---|---|---|
| SAP đổi/khóa internal ext point `clientProposalProvider` | Cao | Ghost text (chỉ dùng platform API) là đường chính; popup là phụ. Đã `resolution:=optional` |
| Gateway/model đổi hành vi (prose leak kiểu mới) | Đã xảy ra 2 lần | Sanitizer nhiều lớp + StatsCollector đo reject-rate để phát hiện sớm; nút Reset model memory |
| Overlay painting vỡ trên theme/font đặc biệt | TB | Cơ chế thu về 1 dòng + marker đã là fallback; thêm pref tắt multi-line |
| Text-parse scope sai → prompt gây hại | TB | Chỉ đưa symbol chắc chắn; luật "ưu tiên bỏ sót" |
| ADT REST cần quyền hệ thống SAP (đã thấy lỗi quota AIQUOTA trên máy user) | Cao | DDIC là P2 spike, không nằm trên critical path |

## 14. Test plan

- **Đã có**: CoreTests 64 assert (JSON, resolver, sanitizer, structure hint, signature lookup,
  cache key, cursor context, skeleton, prompt builder) — chạy mỗi build.
- **Thêm theo phase**: scope extraction (P2'), block balance + tier1 (P3'), redaction (P5'),
  concurrency: fake client trả chậm + đổi ticket → assert không render (P1').
- **SWTBot**: KHÔNG đưa vào (build không có OSGi test harness; chi phí > lợi ích cho 1 dev).
  Thay bằng **manual checklist** trong docs này: 10 thao tác kiểm tra sau mỗi lần cài
  (gõ giữa dòng, comment, xoá nhanh, Tab với popup, undo, đổi editor, file lớn, mất mạng,
  timeout, rate limit).
- **Golden dataset** (P1, mục #16): ≥20 case theo danh sách benchmark của template
  (method đơn giản, itab processing, Open SQL, exception, constructor expression, call
  method đúng kiểu, ABAP Unit, mapping) — chạy qua LiveEndpointTest mở rộng, chấm
  syntax-valid + scope-valid tự động.

## 15. Acceptance criteria tổng

1. Gõ ABAP bình thường không bao giờ thấy: text đè lên code thật, prose/markdown trong
   suggestion, suggestion cho vị trí đã rời khỏi. *(3 điều này đã đạt — giữ bằng test.)*
2. `ENDIF.`/`ENDMETHOD.` và các đóng block: ghost tức thời, không gọi mạng.
3. Suggestion trong method body dùng đúng tên parameter/biến local đã khai báo.
4. Người dùng luôn biết trạng thái plugin qua status indicator; lỗi nền không popup.
5. Stats.csv trả lời được: acceptance rate, latency P50/P95, reject rate theo model.
6. Không secret nào rời máy; object trong exclude list không bao giờ được gửi.

## Giả định & câu hỏi cần xác nhận

1. Gateway 9Router có hỗ trợ `n>1` (nhiều completion/request) không? → quyết định mục #14.
2. ABAP release đích của hệ thống bạn (7.40? 7.5x? BTP Steampunk?) → giá trị default pref.
3. Bạn có quyền gọi ADT REST (`/sap/bc/adt/ddic/...`) trên hệ thống SAP không? → spike #15.
4. Artifact ngoài Class/Interface bạn thực sự edit hằng ngày là gì? (ưu tiên Phase 4').
5. Có cần hỗ trợ đồng nghiệp khác cài không? → quyết định đầu tư p2 update-site vs dropins.

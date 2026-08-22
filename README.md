# Eclipse Extension 9Router — AI Code Assistant

AI-assisted Java code completion for Eclipse, backed by any OpenAI-compatible endpoint. The extension is designed for private gateways and local model servers, with strict separation between connection verification, model selection, and completion state.

## Features

- Java suggestions inside Eclipse Content Assist (`Ctrl+Space`).
- OpenAI-compatible `/v1/models` and `/v1/chat/completions` support.
- Robust SSE streaming parser; supports `finish_reason`, `[DONE]`, and clean EOF.
- Auto and manual model selection.
- Last-known-good model preference and one controlled model failover.
- API key stored in Eclipse Secure Storage.
- Endpoint/model state kept separate so stale model IDs are never reused after connection changes.
- Request timeout, context budgets, token and temperature controls.
- Optional debounced automatic Content Assist, disabled by default.
- No JDT internal APIs and no network work on the UI thread.

## Requirements

- Eclipse IDE 2026-06 / Platform 4.40 or newer.
- Java 21 runtime.
- Eclipse JDT UI.
- An OpenAI-compatible endpoint.

Default endpoint:

```text
http://localhost:20128/v1
```

## Install using the p2 ZIP

1. Extract `casla-eclipse-ai-assistant-0.1.0-p2.zip` to a folder.
2. In Eclipse, open **Help → Install New Software**.
3. Click **Add → Local** and select the extracted folder.
4. Select **Casla AI Code Assistant** and complete installation.
5. Restart Eclipse.

## Dropins fallback

Extract `casla-eclipse-ai-assistant-0.1.0-dropins.zip` directly into the Eclipse installation directory, preserving the `dropins/casla-ai/plugins/...` layout, then restart Eclipse with `-clean` once.

## Configure

Open:

```text
Window → Preferences → AI Code Assistant
```

Enter Base URL and API key, then press **Test connection**. In Auto mode the extension scores usable models rather than selecting the first response item. In Manual mode the model field remains editable, including when `/v1/models` is unavailable.

The API key is encrypted through Eclipse Secure Storage. It is never written to normal Eclipse preferences or logs.

## Use

Open a Java source file and press `Ctrl+Space`. When connection and model states are ready, Eclipse includes an entry named `AI suggestion · <model>` alongside normal JDT proposals.

Automatic suggestions are experimental and disabled by default. When enabled, the standard Content Assist popup may open after Enter, `{`, `=`, or `(` and the configured debounce interval.

## Build

From PowerShell:

```powershell
.\build.ps1
```

Override local tool locations if necessary:

```powershell
.\build.ps1 -EclipseRoot C:\path\to\eclipse -JdkRoot C:\path\to\jdk-21
```

An optional live integration test runs when `AI_CODE_ASSISTANT_API_KEY` is present. The key is read from the process environment and is never written to an artifact:

```powershell
$env:AI_CODE_ASSISTANT_API_KEY = "<temporary-key>"
.\build.ps1
Remove-Item Env:AI_CODE_ASSISTANT_API_KEY
```

Artifacts are written to `dist/`:

```text
casla-eclipse-ai-assistant-0.1.0-p2.zip
casla-eclipse-ai-assistant-0.1.0-dropins.zip
casla-eclipse-ai-assistant-0.1.0-source.zip
SHA256SUMS.txt
```

## Security and privacy

- Plain HTTP is accepted only for localhost/loopback endpoints.
- Remote endpoints must use HTTPS.
- Authorization headers, API keys, prompts, and source code are not logged.
- Context is bounded around the cursor; the whole repository is not uploaded.
- A localhost URL may still proxy requests to a remote provider. Verify the gateway's data handling before using proprietary source code.

## Architecture

```text
Java Editor
  → IJavaCompletionProposalComputer
  → ContextExtractor / PromptBuilder
  → AiRuntime state gate
  → OpenAiCompatibleClient
  → SSE/JSON response
  → stale-document validation
  → standard Eclipse CompletionProposal
```

## License

MIT

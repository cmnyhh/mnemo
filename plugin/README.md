# mnemo-memory-core

Cross-session memory for dsh: each turn is summarized and stored, and the relevant entries are returned to the context before the next turn begins.

Entries are kept in your own [`mnemo`](../README.md) deployment. The plugin writes no local state and contacts no service other than the one configured.

## Install

```bash
npm install mnemo-memory-core
```

Once installed into a dsh profile, a `mnemo-memory-core` row appears on the **Plugins** page, where the service address and token are set.

## Options

| Option | Default | Description |
| --- | --- | --- |
| `serviceUrl` | `http://127.0.0.1:8090` | Address of the mnemo service; `https://<host>` for remote deployments |
| `serviceToken` | empty | Issued on the console **Tokens** page; also accepts `env:MNEMO_SERVICE_TOKEN` or `file:/path` |
| `projectId` | `default` | Distinguishes projects within one service; affects ranking only, not visibility |
| `topK` | `6` | Number of entries injected per turn |
| `serviceTimeoutMs` | `1200` | Read timeout. Recall runs before the prompt is sent, so it cannot block for long |
| `writeTimeoutMs` | `20000` | Write timeout. The server may call a model to extract facts, which is considerably slower |
| `toolName` | `memory_search` | Name of the recall tool exposed to the model |

The token can be supplied through the environment, leaving only a reference in the configuration:

```bash
export MNEMO_SERVICE_TOKEN=mn_xxxxxxxx
```

## Behaviour

**Write.** When a turn ends, the user message and the model's conclusions are condensed into a record and stored. Greetings, empty exchanges and turns with no informational content are skipped.

**Recall.** Before the next turn, the incoming message is used as a query; matching entries are assembled into a context block with source annotations. If the best score does not clear the threshold, nothing is injected.

**Tool.** `memory_search` is registered as well, so the model can query earlier information on its own.

**Failures are reported as failures.** When the service is unreachable, the injected block states that recall failed rather than that no related memory exists. The two are not equivalent to a model.

## Building from source

```bash
cd plugin
npm install
npm run build     # produces index.js
npm run smoke     # mock host: mount, write, next-turn recall, tool call
```

`index.js` is not committed; `prepublishOnly` rebuilds it before publishing so a stale artifact cannot be released.

## License

GNU Affero General Public License v3.0 or later — see [LICENSE](../LICENSE). The plugin is part of mnemo and carries the same terms, including §13 when it talks to a service on someone else's behalf.

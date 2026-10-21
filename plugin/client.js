window.__ModuleLoader__.load({
	id: "mnemo-memory-core",
	factory: (require) => {
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });

		const React = require("react");
		const h = React.createElement;

		/**
		 * 这一行配置页在「插件」页按 `bundle#rowId` 分派，与 ui-plugin-manager 的
		 * rowConfigKey() 一致。写错一个字符页面就不会出现——而且不会报错。
		 */
		const ROW_KEY = "mnemo-memory-core#memory-core";

		const tone = {
			wrap: { display: "grid", gap: "14px", maxWidth: "640px" },
			row: { display: "grid", gap: "6px" },
			label: { fontSize: "12px", opacity: 0.75 },
			input: {
				font: "inherit",
				fontSize: "13px",
				padding: "7px 9px",
				borderRadius: "7px",
				border: "1px solid rgba(128,128,128,.35)",
				background: "transparent",
				color: "inherit",
				width: "100%",
				boxSizing: "border-box",
			},
			actions: { display: "flex", gap: "8px", alignItems: "center" },
			button: {
				font: "inherit",
				fontSize: "13px",
				padding: "6px 14px",
				borderRadius: "7px",
				border: "1px solid rgba(128,128,128,.35)",
				background: "transparent",
				color: "inherit",
				cursor: "pointer",
			},
			hint: { fontSize: "12px", opacity: 0.65, lineHeight: 1.6, margin: 0 },
			note: { fontSize: "12px", minHeight: "18px", margin: 0 },
		};

		function Field(props) {
			return h(
				"label",
				{ style: tone.row },
				h("span", { style: tone.label }, props.label),
				h("input", {
					style: tone.input,
					type: props.type || "text",
					value: props.value,
					placeholder: props.placeholder,
					autoComplete: "off",
					spellCheck: false,
					disabled: props.disabled === true,
					onChange: (event) => props.onChange(event.target.value),
				}),
				props.note ? h("span", { style: tone.hint }, props.note) : null,
			);
		}

		function Summary() {
			return h("span", null, "记忆服务地址与访问令牌");
		}

		/** 页面本体：两个字段 + 保存/清除。值由宿主文档持有，保存即写入 profile 的 patch。 */
		function Page(props) {
			const form = props.form;
			const state = form.state;
			const revision = state.revision;

			const initialUrl = typeof state.value?.serviceUrl === "string" ? state.value.serviceUrl : "";
			const [url, setUrl] = React.useState(initialUrl);
			const [token, setToken] = React.useState("");
			const [touched, setTouched] = React.useState({ url: false, token: false });
			const [busy, setBusy] = React.useState(false);
			const [note, setNote] = React.useState("");

			// 宿主接受一次写入后 revision 会变：此时才用新值重置输入，避免把用户
			// 正在输入的内容冲掉。token 永远从空白开始，宿主不回显它。
			React.useEffect(() => {
				setUrl(typeof state.value?.serviceUrl === "string" ? state.value.serviceUrl : "");
				setToken("");
				setTouched({ url: false, token: false });
				setNote("");
			}, [revision]);

			if (state.status === "unavailable") {
				return h(
					"div",
					{ style: tone.wrap },
					h(
						"p",
						{ style: tone.hint },
						"这个部署没有把插件配置保存在服务端，因此这里无法修改。",
					),
				);
			}

			const writable = state.writable === true;

			const save = async () => {
				const ops = [];
				if (touched.url) ops.push({ op: "set", path: ["serviceUrl"], value: url.trim() });
				if (touched.token && token.trim() !== "") {
					ops.push({ op: "set", path: ["serviceToken"], value: token.trim() });
				}
				if (ops.length === 0) {
					setNote("没有改动。");
					return;
				}
				setBusy(true);
				setNote("保存中…");
				try {
					const accepted = await form.mutate(ops, revision);
					setNote(accepted ? "已保存。令牌立即生效，地址改动重启后生效。" : "保存被拒绝，请检查取值。");
				} catch (error) {
					setNote("保存失败：" + (error && error.message ? error.message : String(error)));
				} finally {
					setBusy(false);
				}
			};

			const clear = async () => {
				setBusy(true);
				setNote("清除中…");
				try {
					const accepted = await form.mutate(
						[
							{ op: "unset", path: ["serviceUrl"] },
							{ op: "unset", path: ["serviceToken"] },
						],
						revision,
					);
					setNote(accepted ? "已清除，恢复默认值。" : "清除被拒绝。");
				} catch (error) {
					setNote("清除失败：" + (error && error.message ? error.message : String(error)));
				} finally {
					setBusy(false);
				}
			};

			return h(
				"div",
				{ style: tone.wrap },
				h(Field, {
					label: "服务地址",
					value: url,
					placeholder: "http://127.0.0.1:8090",
					disabled: !writable || busy,
					onChange: (next) => {
						setUrl(next);
						setTouched((prev) => ({ ...prev, url: true }));
					},
				}),
				h(Field, {
					label: "访问令牌",
					type: "password",
					value: token,
					// 秘密字段不回传，连「有没有值」都不在这个快照里，所以这里不能声称
					// 已设置——只能说明留空的含义。
					placeholder: "留空表示不修改",
					disabled: !writable || busy,
					onChange: (next) => {
						setToken(next);
						setTouched((prev) => ({ ...prev, token: true }));
					},
				}),
				h(
					"div",
					{ style: tone.actions },
					h(
						"button",
						{ style: tone.button, disabled: !writable || busy, onClick: save, type: "button" },
						busy ? "保存中…" : "保存",
					),
					h(
						"button",
						{ style: tone.button, disabled: !writable || busy, onClick: clear, type: "button" },
						"恢复默认",
					),
					h("p", { style: tone.note }, note),
				),
				h(
					"p",
					{ style: tone.hint },
					"令牌在控制台「令牌」页签发。改动写入当前 profile，不会影响插件本体。",
					writable ? "" : "当前部署只在浏览器本地保存设置，刷新即丢。",
				),
			);
		}

		/** 同一个组件要同时应付清单摘要与整页两种 view，因此这里不持有 hook。 */
		function RowConfigPage(props) {
			if (props.view === "summary") return h(Summary, null);
			if (props.form === undefined) return null;
			return h(Page, { form: props.form });
		}

		/** 需要的服务：只用到 slot 注册表；表单与写入由插件页作为 owner 传入。 */
		const inject = ["slots"];

		function apply(ctx) {
			ctx.effect(
				() =>
					ctx.slots.inject("plugins.row.config", () =>
						ctx.slots.register(
							{ name: "plugins.row.config", key: ROW_KEY, order: 50 },
							RowConfigPage,
						),
					),
				"memory-core: 插件页配置面板",
			);
		}

		exports.apply = apply;
		exports.inject = inject;
		return module.exports;
	}
});

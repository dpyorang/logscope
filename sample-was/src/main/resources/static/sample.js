const get = (id) => document.getElementById(id);
let automatic = true;
async function request(path, body) {
  const response = await fetch(
    path,
    body === undefined
      ? {}
      : {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        },
  );
  if (!response.ok)
    throw new Error("요청 실패: 입력과 샘플 서버 상태를 확인해 주세요.");
  return response.json();
}
function showStatus(status) {
  automatic = status.automatic;
  get("instance").textContent = status.instanceId;
  get("auto").textContent =
    `자동 생성: ${automatic ? "켜짐" : "꺼짐"} / ${status.intervalSeconds}초 간격`;
  get("toggle").disabled = false;
}
get("toggle").onclick = async () => {
  get("toggle").disabled = true;
  try {
    showStatus(await request("/api/automatic", { enabled: !automatic }));
  } catch (e) {
    get("notice").textContent = e.message;
    get("toggle").disabled = false;
  }
};
async function generate(level, exception = false, scenario = undefined) {
  get("create").disabled = true;
  get("exception").disabled = true;
  try {
    const result = await request("/api/events", { level, exception, scenario });
    const item = document.createElement("li");
    item.textContent = `${result.instanceId} / ${level}${scenario ? ` / ${scenario}` : ""} / ${result.eventId} / 로그 생성 요청 완료 (중앙 저장 미확인)`;
    get("recent").prepend(item);
    while (get("recent").children.length > 20) get("recent").lastChild.remove();
    get("notice").textContent =
      "로그를 생성했습니다. Vector 전송 후 logscope에서 확인하세요.";
  } catch (e) {
    get("notice").textContent = e.message;
  } finally {
    get("create").disabled = false;
    get("exception").disabled = false;
  }
}
get("generate").onsubmit = (e) => {
  e.preventDefault();
  generate(get("level").value, false, get("scenario").value || undefined);
};
get("exception").onclick = () => {
  const selected = get("scenario").selectedOptions[0];
  generate(
    "ERROR",
    true,
    selected.dataset.level === "ERROR" ? selected.value : undefined,
  );
};
get("scenario").onchange = () => {
  const selected = get("scenario").selectedOptions[0];
  if (selected.dataset.level) get("level").value = selected.dataset.level;
};
get("level").onchange = () => {
  get("scenario").value = "";
};
request("/api/scenarios")
  .then((scenarios) => {
    for (const scenario of scenarios) {
      const option = document.createElement("option");
      option.value = scenario.code;
      option.dataset.level = scenario.level;
      option.textContent = `${scenario.category} / ${scenario.label} (${scenario.level})`;
      get("scenario").append(option);
    }
  })
  .catch((e) => (get("notice").textContent = e.message));
request("/api/status")
  .then(showStatus)
  .catch((e) => (get("notice").textContent = e.message));

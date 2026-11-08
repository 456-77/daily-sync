package com.dailysync.service;

import com.dailysync.entity.OmofunEntry;
import com.dailysync.omofun.OmofunException;
import com.dailysync.omofun.OmofunTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OmoFun 单文件报告 / 完整导出（M11，Rust 版 report.rs 的移植）。
 *
 * <p>报告改成按需生成而不是写盘：管理员打开「报告」时现查 MySQL 拼一个自包含 HTML，
 * 前端用 Blob URL 打开（带 JWT 的 fetch 无法直接塞进 window.open）。这样容器重建、
 * 多实例都不会出现「报告文件过期」的问题；导出同理。
 */
@Service
@RequiredArgsConstructor
public class OmofunReportService {

    private static final String[] STATUS_NAMES = {"未知", "在看", "看完", "暂停"};

    private final OmofunStoreService store;
    private final OmofunTokenService tokenService;
    private final OmofunJobService jobService;
    private final ObjectMapper objectMapper;

    public String generateHtml() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (OmofunEntry entry : store.listAllEntries()) {
            entries.add(reportEntry(entry));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", OmofunTime.now());
        payload.put("accountUid", tokenService.decodeAccountUidSafe());
        payload.put("lastRun", Map.of("summary", lastSummary()));
        payload.put("entries", entries);

        String data;
        try {
            // 防止标题里的 </script> 截断脚本；与 Rust 版 replace('<', "\\u003c") 等价
            data = objectMapper.writeValueAsString(payload).replace("<", "\\u003c");
        } catch (Exception e) {
            throw new OmofunException("生成报告失败: " + e.getMessage(), e);
        }
        return TEMPLATE.replace("__DATA__", data);
    }

    public String exportJson() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", OmofunTime.now());
        payload.put("accountUid", tokenService.decodeAccountUidSafe());
        payload.put("lastRun", Map.of("summary", lastSummary()));
        payload.put("entries", store.listAllEntries());
        payload.put("progressLog", store.listProgressLog(2000));
        payload.put("pollRuns", store.listRuns(30));
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            throw new OmofunException("生成导出失败: " + e.getMessage(), e);
        }
    }

    private String lastSummary() {
        String last = jobService.lastResult();
        return last == null ? "" : last;
    }

    private Map<String, Object> reportEntry(OmofunEntry e) {
        int status = e.getStatus() == null ? 0 : e.getStatus();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", e.getEntryKey());
        m.put("title", e.getTitle() == null ? e.getEntryKey() : e.getTitle());
        m.put("cover", e.getCoverUrl());
        m.put("epIdx", e.getLastEpisodeIndex());
        m.put("epTitle", e.getEpisodeTitle());
        m.put("epId", e.getLastEpisodeId());
        m.put("line", e.getLastLineId());
        m.put("player", e.getPlayerName());
        m.put("progress", e.getProgressSeconds() == null ? 0 : e.getProgressSeconds());
        m.put("total", e.getTotalDurationSeconds() == null ? 0 : e.getTotalDurationSeconds());
        m.put("status", status);
        m.put("statusName", STATUS_NAMES[Math.max(0, Math.min(3, status))]);
        m.put("updatedAt", e.getServerUpdatedAt());
        m.put("serverGone", e.getServerGoneAt());
        m.put("originallyWatchedAt", e.getOriginallyWatchedAt());
        m.put("serverSeen", e.getLastServerSeenAt() != null);
        m.put("source", e.getSource());
        return m;
    }

    /** 与旧版 report.html 同一套模板（深色卡片 + 前端筛选/搜索），数据内联。 */
    private static final String TEMPLATE = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>OmoFun 观看历史存档</title>
<style>
  :root { --bg:#0f1115; --panel:#171a21; --card:#1d212b; --border:#2a2f3c; --text:#e8eaf0;
    --muted:#9aa3b5; --accent:#7c8cff; --accent2:#ff7ca8; --ok:#5ad1a0; --warn:#f2b355; --gone:#ff6b6b; }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--text); font-family:"Microsoft YaHei UI","PingFang SC",system-ui,sans-serif; }
  .wrap { max-width:1180px; margin:0 auto; padding:28px 20px 60px; }
  h1 { font-size:22px; margin:0 0 4px; } h1 .logo { color:var(--accent2); }
  .sub { color:var(--muted); font-size:13px; margin-bottom:18px; }
  .chips { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:16px; }
  .chip { background:var(--panel); border:1px solid var(--border); border-radius:999px; padding:4px 12px; font-size:12.5px; color:var(--muted); }
  .chip b { color:var(--text); font-weight:600; }
  .toolbar { display:flex; flex-wrap:wrap; gap:10px; margin-bottom:22px; }
  .toolbar input[type=search] { flex:1 1 260px; background:var(--panel); border:1px solid var(--border); color:var(--text); border-radius:10px; padding:9px 14px; font-size:14px; outline:none; }
  .toolbar input[type=search]:focus { border-color:var(--accent); }
  .toolbar button { background:var(--panel); border:1px solid var(--border); color:var(--muted); border-radius:10px; padding:9px 14px; font-size:13px; cursor:pointer; }
  .toolbar button.active { color:var(--text); border-color:var(--accent); background:#232946; }
  section { margin-bottom:30px; } section h2 { font-size:16px; margin:0 0 12px; display:flex; align-items:center; gap:8px; }
  section h2 .count { color:var(--muted); font-size:13px; font-weight:400; }
  .dot { width:8px; height:8px; border-radius:50%; display:inline-block; }
  .grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(340px,1fr)); gap:14px; }
  .card { background:var(--card); border:1px solid var(--border); border-radius:14px; padding:12px; display:flex; gap:12px; transition:border-color .15s; }
  .card:hover { border-color:var(--accent); }
  .cover { width:84px; height:112px; border-radius:10px; flex:none; overflow:hidden; position:relative; background:linear-gradient(135deg,#2b3352,#43305c); display:flex; align-items:center; justify-content:center; font-size:34px; font-weight:700; color:#aab4ff; }
  .cover img { width:100%; height:100%; object-fit:cover; }
  .meta { flex:1; min-width:0; display:flex; flex-direction:column; gap:5px; }
  .title { font-size:15px; font-weight:600; line-height:1.3; overflow:hidden; text-overflow:ellipsis; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; }
  .ep { font-size:12.5px; color:var(--muted); }
  .bar { height:6px; border-radius:3px; background:#2a2f3c; overflow:hidden; margin-top:2px; }
  .bar > div { height:100%; background:linear-gradient(90deg,var(--accent),var(--accent2)); border-radius:3px; }
  .num { font-size:12px; color:var(--muted); display:flex; justify-content:space-between; }
  .badges { display:flex; flex-wrap:wrap; gap:6px; margin-top:3px; }
  .badge { font-size:11px; border-radius:6px; padding:2px 8px; border:1px solid var(--border); color:var(--muted); }
  .badge.ok { color:var(--ok); border-color:rgba(90,209,160,.4); }
  .badge.warn { color:var(--warn); border-color:rgba(242,179,85,.4); }
  .badge.gone { color:var(--gone); border-color:rgba(255,107,107,.4); }
  .badge.acc { color:#aab4ff; border-color:rgba(124,140,255,.4); }
  .foot { margin-top:40px; color:var(--muted); font-size:12px; line-height:1.8; }
  @media (max-width:640px) { .grid { grid-template-columns:1fr; } }
</style>
</head>
<body>
<div class="wrap">
  <h1><span class="logo">▶</span> OmoFun 观看历史存档</h1>
  <div class="sub" id="sub"></div>
  <div class="chips" id="chips"></div>
  <div class="toolbar">
    <input id="q" type="search" placeholder="搜索番剧标题 / 关键词…">
    <button data-f="all" class="active">全部</button>
    <button data-f="watching">在看</button>
    <button data-f="done">看完</button>
    <button data-f="paused">暂停</button>
    <button data-f="archived">仅存档</button>
  </div>
  <section id="sec-watching"><h2><span class="dot" style="background:var(--accent)"></span>正在追 <span class="count"></span></h2><div class="grid"></div></section>
  <section id="sec-done"><h2><span class="dot" style="background:var(--ok)"></span>已看完 <span class="count"></span></h2><div class="grid"></div></section>
  <section id="sec-paused"><h2><span class="dot" style="background:var(--warn)"></span>已暂停 <span class="count"></span></h2><div class="grid"></div></section>
  <section id="sec-archived"><h2><span class="dot" style="background:var(--gone)"></span>仅存档（服务端已重置 / 仅本地记录）<span class="count"></span></h2><div class="grid"></div></section>
  <div class="foot" id="foot"></div>
</div>
<script>
const DATA = __DATA__;
const fmtTime = (iso) => { if (!iso) return '—'; const d = new Date(iso); if (isNaN(d)) return iso;
  const p = (x) => String(x).padStart(2,'0'); return d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate())+' '+p(d.getHours())+':'+p(d.getMinutes()); };
const fmtDur = (s) => { s = Math.max(0, Math.trunc(s||0)); const h=Math.floor(s/3600), m=Math.floor((s%3600)/60), x=s%60, p=(v)=>String(v).padStart(2,'0');
  return h>0 ? h+':'+p(m)+':'+p(x) : m+':'+p(x); };
const fmtDay = (iso) => { if (!iso) return '—'; const d = new Date(iso); if (isNaN(d)) return iso.slice(0,10);
  const p = (x) => String(x).padStart(2,'0'); return d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate()); };
const renewTag = (e) => (e.originallyWatchedAt && e.updatedAt && fmtDay(e.originallyWatchedAt) !== fmtDay(e.updatedAt)) ? ' <span style="color:var(--accent)">已续期</span>' : '';
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function categoryOf(e) { if (e.serverGone || !e.serverSeen) return 'archived'; if (e.status === 2) return 'done'; if (e.status === 3) return 'paused'; return 'watching'; }
function cardHtml(e) {
  const pct = e.total > 0 ? Math.min(100, Math.round((e.progress/e.total)*1000)/10) : (e.status===2?100:0);
  const initial = esc((e.title||'?').trim().charAt(0));
  const cover = e.cover ? '<img src="'+esc(e.cover)+'" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">' : '';
  const epLine = [e.epIdx != null ? '第 '+e.epIdx+' 集' : (e.epTitle||''), e.epTitle && e.epIdx != null ? esc(e.epTitle) : ''].filter(Boolean).join(' · ');
  const line = [e.line || e.player].filter(Boolean).join('/');
  const badges = [];
  badges.push('<span class="badge '+(e.status===2?'ok':e.status===3?'warn':'acc')+'">'+esc(e.statusName)+'</span>');
  if (e.serverGone) badges.push('<span class="badge gone">服务端已重置 '+fmtTime(e.serverGone).slice(0,10)+'</span>');
  else if (!e.serverSeen) badges.push('<span class="badge gone">仅本地存档</span>');
  if (line) badges.push('<span class="badge">'+esc(line)+'</span>');
  return '<div class="card"><div class="cover">'+cover+initial+'</div><div class="meta">'+
    '<div class="title" title="'+esc(e.title)+'">'+esc(e.title)+'</div>'+
    '<div class="ep">'+(epLine||'—')+'</div>'+
    '<div class="bar"><div style="width:'+pct+'%"></div></div>'+
    '<div class="num"><span>'+fmtDur(e.progress)+' / '+(e.total?fmtDur(e.total):'—')+'</span><span>'+pct+'%</span></div>'+
    '<div class="num"><span>最后观看：'+fmtDay(e.originallyWatchedAt||e.updatedAt)+renewTag(e)+'</span></div>'+
    '<div class="badges">'+badges.join('')+'</div></div></div>';
}
const sections = { watching:'sec-watching', done:'sec-done', paused:'sec-paused', archived:'sec-archived' };
let activeFilter = 'all';
function render() {
  const q = document.getElementById('q').value.trim().toLowerCase();
  for (const [cat, secId] of Object.entries(sections)) {
    const sec = document.getElementById(secId);
    const grid = sec.querySelector('.grid');
    const cards = DATA.entries.filter((e) => categoryOf(e) === cat)
      .filter((e) => activeFilter === 'all' || activeFilter === cat)
      .filter((e) => !q || (e.title+' '+(e.epTitle||'')+' '+(e.line||'')).toLowerCase().includes(q));
    grid.innerHTML = cards.length ? cards.map(cardHtml).join('') : '';
    sec.querySelector('.count').textContent = cards.length ? cards.length + ' 部' : '';
    sec.style.display = cards.length ? '' : 'none';
  }
}
document.getElementById('q').addEventListener('input', render);
document.querySelectorAll('.toolbar button').forEach((btn) => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.toolbar button').forEach((b) => b.classList.remove('active'));
    btn.classList.add('active'); activeFilter = btn.dataset.f; render();
  });
});
(function init() {
  const run = DATA.lastRun || {};
  document.getElementById('sub').textContent = '账号 UID ' + (DATA.accountUid ?? '—') + ' · 生成于 ' + fmtTime(DATA.generatedAt) + ' · ' + (run.summary || '');
  const counts = { all: DATA.entries.length, watching:0, done:0, paused:0, archived:0 };
  DATA.entries.forEach((e) => { counts[categoryOf(e)] += 1; });
  document.getElementById('chips').innerHTML =
    '<span class="chip">存档总数 <b>'+counts.all+'</b></span>'+
    '<span class="chip">在看 <b>'+counts.watching+'</b></span>'+
    '<span class="chip">看完 <b>'+counts.done+'</b></span>'+
    '<span class="chip">暂停 <b>'+counts.paused+'</b></span>'+
    '<span class="chip">仅存档 <b>'+counts.archived+'</b></span>';
  document.getElementById('foot').innerHTML = '本报告由 daily-sync 的 OmoFun 存档模块按需生成（只读存档，不主动写服务端）。<br>数据源：MySQL daily_sync.omofun_entries · 完整导出见 /api/v1/omofun/export';
  render();
})();
</script>
</body>
</html>
""";
}

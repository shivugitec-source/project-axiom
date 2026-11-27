const OWNER = "shivugitec-source";
const REPO = "project-axiom";
const API = `https://api.github.com/repos/${OWNER}/${REPO}`;
const $ = (id) => document.getElementById(id);
const esc = (value) => String(value ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const date = value => value ? new Date(value).toLocaleString(undefined,{dateStyle:"medium",timeStyle:"short"}) : "—";
const shortSha = value => value ? value.slice(0,7) : "—";
async function github(path){
  const response = await fetch(`${API}/${path}`,{headers:{Accept:"application/vnd.github+json"}});
  if(!response.ok) throw new Error(`GitHub API ${response.status} for ${path}`);
  return response.json();
}
const features = [
 ["Tenant and organization foundation","Implemented","Organization and tenant domain/API foundations are present.","backend/axiom-parent/axiom-api/src/main/java/io/projectaxiom/organization"],
 ["Party domain","Implemented","Party and contact-point controllers and domain components are present.","backend/axiom-parent/axiom-api/src/main/java/io/projectaxiom/party"],
 ["Identity and access security","Implemented","OIDC JWT conversion, tenant context and permission authorization code plus tests.","backend/axiom-parent/axiom-bootstrap/src/main/java/io/projectaxiom/bootstrap/security"],
 ["Database isolation and migration controls","Implemented","Dedicated migrator and migration/role validation are part of the backend foundation.","backend"],
 ["Sugarcane weighment calculation","Partial","Exact gross, tare and net calculation is introduced; procurement, persistence, pricing and settlement are not delivered.","backend/axiom-parent/axiom-business"],
 ["Sugar business workflows","Planned","Next workflows require explicit business requirements; no procurement, pricing, quality, recovery, weighbridge or settlement rules are assumed.","Roadmap"],
 ["Operations UI and mobile app","Planned","No production operator frontend or Android client is currently delivered.","Roadmap"],
 ["Production deployment and tenant onboarding","Planned","Production identity-provider setup, tenant provisioning and production runtime deployment remain future work.","Roadmap"]
];
const capabilities = [
 ["Multi-tenant foundation","Organization and tenant boundary code"],
 ["Party management","Party and contact-point API foundations"],
 ["Identity & access","OIDC/JWT and tenant permission checks"],
 ["Database safeguards","Tenant isolation and restricted migration/runtime roles"],
 ["Schema evolution","Dedicated Flyway migration artifact"],
 ["Sugar domain starter","Exact-precision weighment value object only"]
];
const modules = [
 ["COMMON","axiom-common","Shared primitives and cross-cutting types."],
 ["CORE","axiom-core","Platform domain foundation and reusable core models."],
 ["BUSINESS","axiom-business","Reusable business capabilities and domain modules."],
 ["API","axiom-api","HTTP/API adapters and resource controllers."],
 ["BOOTSTRAP","axiom-bootstrap","Application assembly, runtime security and startup."],
 ["MIGRATOR","axiom-migrator","Isolated schema migration execution."]
];
const roadmap = [
 ["01 · CURRENT","Platform hardening","Continue closing tenant, IAM, database isolation and migration edge cases with repeatable CI evidence."],
 ["02 · NEXT","Sugar requirements gate","Weighment is the only implemented sugar capability. Confirm the next business workflow and its rules before extending the domain model; no procurement, pricing, quality, recovery, weighbridge or settlement semantics are assumed."],
 ["03 · AFTER","One approved sugar increment","After requirements are approved, implement one bounded workflow with explicit invariants, authorization, auditability and integration tests."],
 ["04 · LATER","Operator experience","Design responsive factory/field workflows after the domain model and security boundaries stabilize."],
 ["05 · LATER","Production readiness","Select hosting/runtime topology, external identity provider, secrets management, observability, backups and recovery."]
];
function renderStatic(){
  $("capability-list").innerHTML = capabilities.map(([name,detail])=>`<div class="capability"><b>${esc(name)}</b><small>${esc(detail)}</small></div>`).join("");
  $("module-grid").innerHTML = modules.map(([code,name,detail])=>`<article class="module-card"><span class="module-code">${esc(code)}</span><h3>${esc(name)}</h3><p>${esc(detail)}</p></article>`).join("");
  $("feature-table").innerHTML = '<div class="feature-row header"><div>CAPABILITY</div><div>STATUS</div><div>EVIDENCE / SCOPE</div></div>' + features.map(([name,status,detail,evidence])=>`<div class="feature-row"><div class="feature-name">${esc(name)}</div><div><span class="pill ${status==="Implemented"?"green":status==="Partial"?"amber":"gray"}">${esc(status)}</span></div><div class="feature-evidence">${esc(detail)} ${evidence.startsWith("backend/")?`<a class="text-link" href="https://github.com/${OWNER}/${REPO}/tree/main/${evidence}" target="_blank" rel="noreferrer">Evidence ↗</a>`:""}</div></div>`).join("");
  $("roadmap-list").innerHTML = roadmap.map(([phase,title,detail])=>`<article class="roadmap-item"><div class="roadmap-phase">${esc(phase)}</div><div><h3>${esc(title)}</h3><p>${esc(detail)}</p></div></article>`).join("");
}
function statusClass(run){if(run.conclusion==="success")return "success";if(run.conclusion==="failure"||run.conclusion==="cancelled")return "failure";return "pending";}
function runLabel(run){return run.conclusion || run.status || "unknown";}
async function refresh(){
  $("refresh").disabled=true;$("refresh").textContent="↻ Loading…";
  const results = await Promise.allSettled([
    github("commits?per_page=5"),
    github("actions/runs?per_page=8"),
    github("pulls?state=open&per_page=10"),
    github("")
  ]);
  const [commits,runs,pulls,repo] = results;
  if(repo.status==="fulfilled"){
    const branch=repo.value.default_branch||"main";
    const latestMainCommit=commits.status==="fulfilled"?commits.value[0]:null;
    $("main-sha").textContent=shortSha(latestMainCommit?.sha);
    const mainCommitDate=latestMainCommit?.commit?.committer?.date||latestMainCommit?.commit?.author?.date;
    $("main-updated").textContent=`Default branch: ${branch} · ${mainCommitDate?date(mainCommitDate):"commit date unavailable"}`;
  } else {$("main-sha").textContent="Unavailable";$("main-updated").textContent=repo.reason.message;}
  if(runs.status==="fulfilled"){
    const list=runs.value.workflow_runs||[],latest=list[0];
    $("ci-status").textContent=latest?runLabel(latest):"No runs";
    $("ci-detail").innerHTML=latest?`<span class="status-dot ${statusClass(latest)}"></span>${esc(latest.name)} · #${esc(latest.run_number)} · ${esc(date(latest.updated_at))}`:"No workflow runs found";
    $("workflow-list").innerHTML=list.length?list.map(run=>`<div class="activity-item"><a href="${esc(run.html_url)}" target="_blank" rel="noreferrer">${esc(run.name)} · #${esc(run.run_number)} ↗</a><div class="activity-meta"><span><i class="status-dot ${statusClass(run)}"></i>${esc(runLabel(run))}</span><span>${esc(run.head_branch)}</span><span>${esc(shortSha(run.head_sha))}</span><span>${esc(date(run.updated_at))}</span></div></div>`).join(""):'<p class="section-copy">No workflow runs available.</p>';
  }else{$("ci-status").textContent="Unavailable";$("ci-detail").textContent=runs.reason.message;$("workflow-list").textContent="Could not load workflow data.";}
  if(pulls.status==="fulfilled"){
    const list=pulls.value||[];$("open-pr-count").textContent=list.length;
    $("pr-list").innerHTML=list.length?list.map(pr=>`<div class="activity-item"><a href="${esc(pr.html_url)}" target="_blank" rel="noreferrer">#${esc(pr.number)} · ${esc(pr.title)} ↗</a><div class="activity-meta"><span>${esc(pr.user?.login||"unknown")}</span><span>Updated ${esc(date(pr.updated_at))}</span></div></div>`).join(""):'<p class="section-copy">No open pull requests.</p>';
  }else{$("open-pr-count").textContent="—";$("pr-list").textContent="Could not load pull requests.";}
  if(commits.status==="fulfilled"){
    const list=commits.value||[];
    $("commit-list").innerHTML=list.map(commit=>`<div class="activity-item"><a href="${esc(commit.html_url)}" target="_blank" rel="noreferrer">${esc(commit.commit?.message?.split("\n")[0]||"Commit")} ↗</a><div class="activity-meta"><span>${esc(shortSha(commit.sha))}</span><span>${esc(date(commit.commit?.author?.date))}</span></div></div>`).join("");
    if(list[0]) $("main-sha").textContent=shortSha(list[0].sha);
  }else{$("commit-list").textContent="Could not load commits.";}
  const now=new Date().toLocaleString();$("last-refresh").textContent=`Updated ${now}`;$("footer-time").textContent=now;
  $("refresh").disabled=false;$("refresh").innerHTML="↻ <span>Refresh data</span>";
}
document.querySelectorAll(".nav-link").forEach(link=>link.addEventListener("click",event=>{
  event.preventDefault();const view=link.dataset.view;
  document.querySelectorAll(".nav-link").forEach(item=>item.classList.toggle("active",item===link));
  document.querySelectorAll(".view").forEach(section=>section.classList.toggle("active",section.id===`view-${view}`));
  const titles={overview:"Project overview",architecture:"Architecture explorer",features:"Feature tracker",delivery:"Engineering delivery",roadmap:"Project roadmap"};
  $("page-title").textContent=titles[view]||"Project overview";history.replaceState(null,"",`#${view}`);
}));
$("refresh").addEventListener("click",refresh);
renderStatic();refresh();

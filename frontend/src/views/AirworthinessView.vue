<template>
  <div>
    <!-- 顶部总览：计数全部来自服务端 -->
    <section class="panel">
      <div class="rel-overview">
        <div class="rel-count"><b>{{ ov.activeDirectiveCount }}</b><span>生效指令</span></div>
        <div class="rel-count" :class="{ 'is-invalid': ov.overdue > 0 }"><b>{{ ov.overdue }}</b><span>超期</span></div>
        <div class="rel-count is-pending"><b>{{ ov.dueSoon }}</b><span>临期</span></div>
        <div class="rel-count" :class="{ 'is-invalid': ov.pendingVerify > 0 }"><b>{{ ov.pendingVerify }}</b><span>待核实</span></div>
        <div class="rel-count is-released"><b>{{ ov.complied }}</b><span>已落实</span></div>
        <div class="rel-count"><b>{{ ov.notDue }}</b><span>未到期</span></div>
        <div class="rel-count"><b>{{ ov.notApplicable }}</b><span>不适用（清单行）</span></div>
        <div class="rel-count" :class="{ 'is-pending': ov.runningJobCount > 0 }">
          <b>{{ ov.runningJobCount }}</b><span>重算进行中</span>
        </div>
        <div class="rel-gate is-ok" style="min-width:260px">
          <div style="font-size:12px;color:var(--mro-text-sub)">判定口径</div>
          <div style="font-size:12px;line-height:1.7">
            状态/余量/进度全部由服务端按当前日期与飞机累计值计算；三类阈值（日历日期·飞行小时·起落次数）任一先到按最早限制执行。
          </div>
        </div>
      </div>
    </section>

    <el-tabs v-model="tab" class="ad-tabs" @tab-change="onTab">
      <!-- ===================== 指令目录 ===================== -->
      <el-tab-pane label="指令目录" name="directives">
        <section class="panel">
          <div class="panel-head">
            <h3>适航指令目录</h3>
            <span class="panel-sub">同编号指令多修订版并存；旧版结果与证据冻结保留，新版按新范围/阈值重算，绝不覆盖历史</span>
            <div class="panel-extra">
              <el-button size="small" @click="loadDirectives">刷新</el-button>
              <el-button size="small" type="primary" @click="openCreate">发布指令</el-button>
            </div>
          </div>
          <el-table :data="directives" border stripe size="small">
            <el-table-column prop="adNo" label="指令编号" width="150" />
            <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
            <el-table-column prop="issuer" label="发布机构" width="90" />
            <el-table-column label="当前版/生效日" width="130">
              <template #default="{ row }">
                <div>R{{ row.currentRevisionNo || '-' }}</div>
                <div class="muted" style="font-size:11px">{{ fmtDate(row.effectiveDate) }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="revisionCount" label="版本数" width="70" />
            <el-table-column label="超期" width="64">
              <template #default="{ row }"><span :class="{ 'ad-bad': row.overdue > 0 }">{{ row.overdue }}</span></template>
            </el-table-column>
            <el-table-column label="临期" width="64">
              <template #default="{ row }"><span :class="{ 'ad-warn': row.dueSoon > 0 }">{{ row.dueSoon }}</span></template>
            </el-table-column>
            <el-table-column label="待核实" width="70">
              <template #default="{ row }"><span :class="{ 'ad-warn': row.pendingVerify > 0 }">{{ row.pendingVerify }}</span></template>
            </el-table-column>
            <el-table-column label="已落实" width="70">
              <template #default="{ row }"><span class="ad-ok">{{ row.complied }}</span></template>
            </el-table-column>
            <el-table-column label="操作" width="300" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openVersions(row)">版本/范围</el-button>
                <el-button link type="success" @click="openEdit(row)">编辑范围阈值</el-button>
                <el-button link type="warning" @click="openRevise(row)">新增修订版</el-button>
                <el-button link @click="goCompliance(row)">落实清单</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <!-- ===================== 飞机落实清单 ===================== -->
      <el-tab-pane label="飞机落实清单" name="compliance">
        <section class="panel">
          <div class="panel-head">
            <h3>飞机落实清单</h3>
            <span class="panel-sub">未到期/临期/超期/待核实/不适用/已落实——服务端实时判定，前端不计算期限</span>
            <div class="panel-extra">
              <el-button size="small" @click="loadCompliance">刷新</el-button>
            </div>
          </div>

          <div class="inline-form" style="margin-bottom:10px">
            <el-select v-model="filter.directiveId" placeholder="全部指令" clearable filterable style="width:230px" @change="onDirectiveFilter">
              <el-option v-for="d in directives" :key="d.id" :label="d.adNo + ' ' + d.title" :value="d.id" />
            </el-select>
            <el-select v-model="filter.revisionId" placeholder="全部修订版（默认仅当前版）" clearable style="width:250px">
              <el-option v-for="r in filterRevisions" :key="r.id"
                         :label="d(r).adNo + ' R' + r.revisionNo + (r.status === 'SUPERSEDED' ? '（历史冻结）' : '')" :value="r.id" />
            </el-select>
            <el-select v-model="filter.model" placeholder="全部机型" clearable style="width:130px">
              <el-option v-for="m in models" :key="m" :label="m" :value="m" />
            </el-select>
            <el-input v-model="filter.tailNo" placeholder="机号如 B-6688" clearable style="width:150px" />
            <el-select v-model="filter.status" placeholder="全部状态" clearable style="width:130px">
              <el-option v-for="s in statusOptions" :key="s.code" :label="s.name" :value="s.code" />
            </el-select>
            <el-checkbox v-model="filter.includeHistory">含历史版本结果</el-checkbox>
            <el-button size="small" type="primary" @click="loadCompliance">查询</el-button>
          </div>

          <el-table :data="compliances" border stripe size="small" height="560">
            <el-table-column label="状态" width="92" fixed>
              <template #default="{ row }">
                <el-tag size="small" :type="statusTag(row.status)">{{ row.statusName }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="adNo" label="指令" width="140" fixed>
              <template #default="{ row }">
                {{ row.adNo }}
                <span class="muted" style="font-size:11px"> R{{ row.revisionNo }}</span>
                <el-tag v-if="row.revStatus === 'SUPERSEDED'" size="small" type="info" effect="plain">历史</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="tailNo" label="机号" width="92" />
            <el-table-column label="机型" width="80">
              <template #default="{ row }">{{ row.modelName }}</template>
            </el-table-column>
            <el-table-column label="总FH/起落" width="120">
              <template #default="{ row }">
                <span v-if="row.totalMissing" class="ad-warn">累计值缺失</span>
                <span v-else>{{ fmtNum(row.totalFh) }} / {{ row.totalCycles }}</span>
              </template>
            </el-table-column>
            <el-table-column label="最早限制" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.governingKindName" size="small" type="warning" effect="plain">{{ row.governingKindName }}</el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="日期余量(天)" width="100">
              <template #default="{ row }">
                <span v-if="row.daysLeft === null || row.daysLeft === undefined" class="muted">—</span>
                <span :class="marginClass(row.daysLeft)">{{ row.daysLeft }}</span>
              </template>
            </el-table-column>
            <el-table-column label="FH余量" width="92">
              <template #default="{ row }">
                <span v-if="row.fhLeft === null || row.fhLeft === undefined" class="muted">—</span>
                <span :class="marginNum(row.fhLeft)">{{ fmtNum(row.fhLeft) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="起落余量" width="92">
              <template #default="{ row }">
                <span v-if="row.cyclesLeft === null || row.cyclesLeft === undefined" class="muted">—</span>
                <span :class="marginClass(row.cyclesLeft)">{{ row.cyclesLeft }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="pendingReason" label="待核实原因" min-width="200" show-overflow-tooltip>
              <template #default="{ row }"><span class="ad-warn">{{ row.pendingReason }}</span></template>
            </el-table-column>
            <el-table-column label="操作" width="210" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status !== 'COMPLIED'" link type="primary" @click="openComply(row)">登记落实</el-button>
                <el-button v-if="row.status === 'COMPLIED'" link type="primary" @click="openDetail(row)">证据/详情</el-button>
                <el-button v-if="row.status === 'PENDING_VERIFY'" link type="warning" @click="openDetail(row)">核实</el-button>
                <el-button link @click="openDetail(row)">记录</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <!-- ===================== 批量重算 ===================== -->
      <el-tab-pane label="批量重算" name="batch">
        <section class="panel">
          <div class="panel-head">
            <h3>批量重算任务</h3>
            <span class="panel-sub">范围/进度/成功数量/失败原因服务端持久化；逐架短事务执行，不阻塞普通查询与飞行登记</span>
            <div class="panel-extra">
              <el-button size="small" @click="loadBatches">刷新</el-button>
              <el-button size="small" type="primary" :loading="batchStarting" @click="manualRecompute">对当前选中修订版发起重算</el-button>
            </div>
          </div>
          <el-table :data="batches" border stripe size="small">
            <el-table-column prop="id" label="#" width="56" />
            <el-table-column prop="adNo" label="指令" width="140">
              <template #default="{ row }">{{ row.adNo }} <span class="muted">R{{ row.revisionNo }}</span></template>
            </el-table-column>
            <el-table-column prop="triggerName" label="触发" width="100" />
            <el-table-column prop="operator" label="操作人" width="90" />
            <el-table-column prop="scopeNote" label="本次重算范围" min-width="280" show-overflow-tooltip />
            <el-table-column label="进度" width="210">
              <template #default="{ row }">
                <el-progress :percentage="pct(row)" :status="row.status === 'PARTIAL_FAILED' ? 'exception' : (row.status === 'DONE' ? 'success' : '')" />
                <div style="font-size:11px">
                  共 {{ row.totalCount }} · 成功 <b class="ad-ok">{{ row.successCount }}</b>
                  · 失败 <b :class="{ 'ad-bad': row.failedCount > 0 }">{{ row.failedCount }}</b>
                  · 待处理 {{ row.pendingCount }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="92">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'DONE' ? 'success' : (row.status === 'PARTIAL_FAILED' ? 'danger' : 'warning')">
                  {{ row.statusName }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="170">
              <template #default="{ row }">
                <el-button link type="primary" @click="toggleItems(row)">{{ expandedBatch === row.id ? '收起明细' : '查看明细' }}</el-button>
                <el-button v-if="row.failedCount > 0 || row.status === 'PARTIAL_FAILED'"
                           link type="danger" :loading="row._retrying" @click="retryBatch(row)">重试失败项</el-button>
              </template>
            </el-table-column>
          </el-table>

          <template v-for="job in visibleItemJobs" :key="job.id">
            <div v-if="expandedBatch === job.id" class="ad-items">
              <div v-if="job.lastError" class="ad-fail-box">最近失败：{{ job.lastError }}</div>
              <el-table :data="job.items" border size="small">
                <el-table-column prop="tailNo" label="机号" width="100" />
                <el-table-column prop="model" label="机型" width="90" />
                <el-table-column label="状态" width="90">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : (row.status === 'FAILED' ? 'danger' : 'info')">
                      {{ row.statusName }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="attempt" label="尝试次数" width="80" />
                <el-table-column prop="failReason" label="失败原因（可重试，重试不重复生成结果）" min-width="300" show-overflow-tooltip />
              </el-table>
            </div>
          </template>
        </section>
      </el-tab-pane>

      <!-- ===================== 状态变化与证据记录 ===================== -->
      <el-tab-pane label="状态与证据记录" name="events">
        <section class="panel">
          <div class="panel-head">
            <h3>状态变化 / 证据 / 关联记录（append-only）</h3>
            <span class="panel-sub">修订、范围阈值变化、工卡关联与失效、证据增减、状态流转全部可追溯</span>
            <div class="panel-extra">
              <el-select v-model="eventFilter.directiveId" placeholder="全部指令" clearable filterable
                         style="width:240px;margin-right:8px" @change="loadEvents">
                <el-option v-for="d in directives" :key="d.id" :label="d.adNo + ' ' + d.title" :value="d.id" />
              </el-select>
              <el-button size="small" @click="loadEvents">刷新</el-button>
            </div>
          </div>
          <el-timeline>
            <el-timeline-item v-for="e in events" :key="e.id"
                              :timestamp="fmtTime(e.eventAt)" placement="top"
                              :type="eventColor(e.eventType)">
              <div>
                <el-tag size="small" effect="plain">{{ e.eventName }}</el-tag>
                <b style="margin:0 6px">{{ e.adNo }} R{{ e.revisionNo }}</b>
                <span v-if="e.tailNo" class="muted">{{ e.tailNo }}</span>
                <span class="muted" style="margin-left:8px">· {{ e.operator }}</span>
              </div>
              <div style="margin:4px 0;font-size:13px">{{ e.detail }}</div>
              <div v-if="e.fromStatusName || e.toStatusName" style="font-size:12px">
                <el-tag size="small" type="info">{{ e.fromStatusName || '（新建）' }}</el-tag>
                →
                <el-tag size="small" :type="statusTag(e.toStatus)">{{ e.toStatusName }}</el-tag>
              </div>
            </el-timeline-item>
          </el-timeline>
        </section>
      </el-tab-pane>
    </el-tabs>

    <!-- ===================== 发布/修订/编辑 对话框 ===================== -->
    <el-dialog v-model="revDialog.visible" :title="revDialog.title" width="760px" top="6vh">
      <el-form :model="revDialog.form" label-width="120px">
        <template v-if="revDialog.mode === 'create'">
          <el-form-item label="指令编号" required><el-input v-model="revDialog.form.adNo" placeholder="如 CAD-2026-09-01" /></el-form-item>
          <el-form-item label="标题" required><el-input v-model="revDialog.form.title" /></el-form-item>
          <el-form-item label="发布机构" required>
            <el-select v-model="revDialog.form.issuer" style="width:200px" allow-create filterable>
              <el-option label="CAAC 民航局" value="CAAC" />
              <el-option label="FAA" value="FAA" />
              <el-option label="EASA" value="EASA" />
            </el-select>
          </el-form-item>
          <el-form-item label="首版修订号" required><el-input v-model="revDialog.form.revisionNo" placeholder="如 0" style="width:120px" /></el-form-item>
        </template>
        <el-form-item label="生效日期">
          <el-date-picker v-model="revDialog.form.effectiveDate" type="date" value-format="YYYY-MM-DD" style="width:200px" />
        </el-form-item>

        <el-divider content-position="left">截止阈值（三类可组合，任一先到按最早限制执行；留空=该维度不设）</el-divider>
        <el-form-item label="日历日期阈值">
          <el-date-picker v-model="revDialog.form.dueDate" type="date" value-format="YYYY-MM-DD" style="width:200px" />
          <span class="muted" style="margin-left:10px">到期日历日</span>
        </el-form-item>
        <el-form-item label="飞行小时阈值">
          <el-input-number v-model="revDialog.form.dueFh" :min="0" :step="100" controls-position="right" style="width:200px" />
        </el-form-item>
        <el-form-item label="起落次数阈值">
          <el-input-number v-model="revDialog.form.dueCycles" :min="0" :step="100" controls-position="right" style="width:200px" />
        </el-form-item>

        <el-divider content-position="left">临期窗口（逐维分别配置，提前多少进入“临期”）</el-divider>
        <el-form-item label="提前天数">
          <el-input-number v-model="revDialog.form.warnDays" :min="0" controls-position="right" style="width:200px" />
        </el-form-item>
        <el-form-item label="提前飞行小时">
          <el-input-number v-model="revDialog.form.warnFh" :min="0" :step="10" controls-position="right" style="width:200px" />
        </el-form-item>
        <el-form-item label="提前起落次数">
          <el-input-number v-model="revDialog.form.warnCycles" :min="0" :step="10" controls-position="right" style="width:200px" />
        </el-form-item>
        <el-alert v-if="revDialog.liveIssue" :title="revDialog.liveIssue" type="warning" :closable="false"
                  style="margin:6px 0" />

        <el-divider content-position="left">适用机型与机号范围（两界都留空=该机型全部；只填一边=边界不完整→待核实）</el-divider>
        <div v-for="(s, idx) in revDialog.form.scopes" :key="idx" class="inline-form" style="margin-bottom:8px">
          <el-select v-model="s.model" placeholder="机型" style="width:120px">
            <el-option v-for="m in models" :key="m" :label="m" :value="m" />
          </el-select>
          <el-input v-model="s.tailLower" placeholder="机号下界(闭)" style="width:160px" />
          <span>~</span>
          <el-input v-model="s.tailUpper" placeholder="机号上界(闭)" style="width:160px" />
          <el-button link type="danger" @click="revDialog.form.scopes.splice(idx, 1)">删除</el-button>
        </div>
        <el-button size="small" @click="revDialog.form.scopes.push({ model: 'B737', tailLower: '', tailUpper: '' })">+ 增加机型范围</el-button>

        <el-form-item v-if="revDialog.mode === 'create'" label="演示失败机号" style="margin-top:12px">
          <el-input v-model="revDialog.form.demoFailTails" placeholder="可选：仅验收演示，首次批量重算强制失败的机号（逗号分隔）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="revDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="saveRevision">保存并触发机队重算</el-button>
      </template>
    </el-dialog>

    <!-- ===================== 版本抽屉 ===================== -->
    <el-drawer v-model="versionDrawer" title="修订版本与适用范围" size="60%">
      <template v-if="activeDirective">
        <h4>{{ activeDirective.adNo }} {{ activeDirective.title }}</h4>
        <el-table :data="allRevisions" border size="small">
          <el-table-column prop="revisionNo" label="版本" width="70">
            <template #default="{ row }">R{{ row.revisionNo }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="生效日" width="110">
            <template #default="{ row }">{{ fmtDate(row.effectiveDate) }}</template>
          </el-table-column>
          <el-table-column label="截止阈值（日期 / FH / 起落）" min-width="240">
            <template #default="{ row }">
              {{ fmtDate(row.dueDate) || '—' }} / {{ row.dueFh ?? '—' }} / {{ row.dueCycles ?? '—' }}
            </template>
          </el-table-column>
          <el-table-column label="临期窗口（天 / FH / 起落）" min-width="200">
            <template #default="{ row }">{{ row.warnDays ?? '—' }} / {{ row.warnFh ?? '—' }} / {{ row.warnCycles ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button v-if="row.status === 'ACTIVE'" link type="primary" @click="openEdit(activeDirective, row)">编辑</el-button>
              <el-button link @click="viewBatchOf(row)">重算记录</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-divider />
        <div v-for="rev in allRevisions" :key="rev.id" class="ad-scope-block">
          <div class="ad-scope-head">
            R{{ rev.revisionNo }} 适用范围
            <el-tag size="small" :type="rev.status === 'ACTIVE' ? 'success' : 'info'">{{ rev.statusName }}</el-tag>
          </div>
          <el-alert v-if="rev.configIssue" :title="rev.configIssue" type="error" :closable="false" style="margin:6px 0" />
          <el-tag v-for="s in rev.scopes" :key="s.id" size="small" class="ad-scope-tag"
                  :type="(!s.tailLower || !s.tailUpper) && (s.tailLower || s.tailUpper) ? 'danger' : 'info'">
            {{ s.coverageText }}
          </el-tag>
        </div>
      </template>
    </el-drawer>

    <!-- ===================== 登记落实对话框 ===================== -->
    <el-dialog v-model="complyDialog.visible" title="登记适航指令落实" width="680px">
      <template v-if="complyDialog.row">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="指令">{{ complyDialog.row.adNo }} R{{ complyDialog.row.revisionNo }}</el-descriptions-item>
          <el-descriptions-item label="飞机">{{ complyDialog.row.tailNo }} · {{ complyDialog.row.modelName }}</el-descriptions-item>
          <el-descriptions-item label="当前状态">
            <el-tag size="small" :type="statusTag(complyDialog.row.status)">{{ complyDialog.row.statusName }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="操作人">
            <el-input v-model="complyDialog.operator" placeholder="姓名" style="width:160px" />
          </el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">关联同机已关闭工卡（至少一张；重复关联不会生成第二条）</el-divider>
        <el-checkbox-group v-model="complyDialog.cardIds">
          <el-checkbox v-for="c in complyDialog.cards" :key="c.cardId" :label="c.cardId" border style="margin:4px">
            {{ c.cardNo }} {{ c.title }}
            <span class="muted">（{{ c.checkTypeName }}，关闭 {{ fmtTime(c.endTime) }}）</span>
          </el-checkbox>
        </el-checkbox-group>
        <div v-if="!complyDialog.cards.length" class="ad-fail-box">
          该机没有“已关闭”工卡。请先到「定检工卡」完成并检验关闭一张工卡后再登记落实。
        </div>

        <el-divider content-position="left">落实证据（登记凭据编号 或 上传文件，至少一种）</el-divider>
        <el-input v-model="complyDialog.refNo" placeholder="可追溯凭据编号：签署页/归档号/链接，如 ELT-BAT-20260911-013" />
        <el-input v-model="complyDialog.note" placeholder="证据备注（可选）" style="margin-top:8px" />
        <el-upload style="margin-top:8px" :auto-upload="false" :limit="1" :on-change="onPickFile" :on-remove="() => (complyDialog.file = null)"
                   :file-list="complyDialog.fileList">
          <el-button size="small">选择证据文件（不上传，点“登记落实”一起提交）</el-button>
        </el-upload>
      </template>
      <template #footer>
        <el-button @click="complyDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="complyDialog.saving" @click="doComply">登记落实</el-button>
      </template>
    </el-dialog>

    <!-- ===================== 落实详情抽屉（证据/工卡/核实/记录） ===================== -->
    <el-drawer v-model="detailDrawer" title="落实详情 · 证据与状态变化" size="64%">
      <template v-if="detail">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="指令">{{ detail.adNo }} R{{ detail.revisionNo }}</el-descriptions-item>
          <el-descriptions-item label="飞机">{{ detail.tailNo }} · {{ detail.modelName }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="statusTag(detail.status)">{{ detail.statusName }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="日期余量">{{ detail.daysLeft ?? '—' }} 天</el-descriptions-item>
          <el-descriptions-item label="FH余量">{{ fmtNum(detail.fhLeft) }}</el-descriptions-item>
          <el-descriptions-item label="起落余量">{{ detail.cyclesLeft ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="落实时间">{{ fmtTime(detail.compiledAt) }}</el-descriptions-item>
          <el-descriptions-item label="落实人">{{ detail.compiledBy || '—' }}</el-descriptions-item>
          <el-descriptions-item label="判定版本">v{{ detail.factVersion }}</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="detail.pendingReason" :title="detail.pendingReason" type="warning" :closable="false" style="margin:10px 0" />

        <el-divider content-position="left">关联工卡（历史关联不删除；失效会标红）</el-divider>
        <el-table :data="detail.cards" border size="small">
          <el-table-column prop="cardNo" label="工卡号" width="130" />
          <el-table-column prop="title" label="内容" min-width="160" show-overflow-tooltip />
          <el-table-column label="当前归属/状态" width="180">
            <template #default="{ row }">
              <span :class="{ 'ad-bad': !row.valid }">{{ row.currentTailNo || '—' }} · {{ row.currentStatusName || '已不存在' }}</span>
              <div v-if="row.invalidReason" class="ad-bad" style="font-size:11px">{{ row.invalidReason }}</div>
            </template>
          </el-table-column>
          <el-table-column label="有效性" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.valid ? 'success' : 'danger'">{{ row.valid ? '仍有效' : '已失效' }}</el-tag>
            </template>
          </el-table-column>
        </el-table>

        <el-divider content-position="left">落实证据（缺失只标记不删除）</el-divider>
        <el-table :data="detail.evidences" border size="small">
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ row.evTypeName }}</template>
          </el-table-column>
          <el-table-column label="凭据/文件" min-width="200">
            <template #default="{ row }">
              <span v-if="row.refNo">{{ row.refNo }}</span>
              <a v-else-if="!row.missing" :href="downloadUrl(row.id)" target="_blank">{{ row.fileName }}</a>
              <span v-else class="ad-bad">{{ row.fileName }}（缺失）</span>
              <div class="muted" style="font-size:11px">{{ row.note }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="uploadedBy" label="登记人" width="90" />
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button v-if="!row.missing" link type="danger" @click="markMissing(row)">标记缺失</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="inline-form" style="margin:12px 0">
          <el-button size="small" type="primary" @click="addEvidenceDialog = true">登记证据编号</el-button>
          <el-upload :show-file-list="false" :before-upload="() => false" :on-change="onAddFile" style="display:inline-block;margin-left:8px">
            <el-button size="small">上传证据文件</el-button>
          </el-upload>
          <el-button size="small" type="warning" @click="reverifyDialog = true">人工重新核实</el-button>
        </div>

        <el-divider content-position="left">该机该修订版状态变化记录</el-divider>
        <el-timeline>
          <el-timeline-item v-for="e in detailEvents" :key="e.id" :timestamp="fmtTime(e.eventAt)" placement="top"
                            :type="eventColor(e.eventType)">
            <el-tag size="small" effect="plain">{{ e.eventName }}</el-tag>
            <span style="margin-left:6px;font-size:13px">{{ e.detail }}</span>
            <div v-if="e.fromStatusName || e.toStatusName" style="font-size:12px">
              {{ e.fromStatusName || '（新建）' }} → {{ e.toStatusName }} · {{ e.operator }}
            </div>
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>

    <!-- 追加证据编号 -->
    <el-dialog v-model="addEvidenceDialog" title="登记落实证据" width="460px" append-to-body>
      <el-form label-width="80px">
        <el-form-item label="登记人"><el-input v-model="addEv.operator" /></el-form-item>
        <el-form-item label="凭据编号"><el-input v-model="addEv.refNo" placeholder="签署页/归档号/链接" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="addEv.note" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addEvidenceDialog = false">取消</el-button>
        <el-button type="primary" @click="doAddEvidence">保存</el-button>
      </template>
    </el-dialog>

    <!-- 人工核实 -->
    <el-dialog v-model="reverifyDialog" title="人工重新核实" width="460px" append-to-body>
      <el-form label-width="80px">
        <el-form-item label="核实人"><el-input v-model="reverify.operator" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="reverify.note" type="textarea" placeholder="如：已补工作业并重新关闭工卡/证据已补齐" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reverifyDialog = false">取消</el-button>
        <el-button type="primary" @click="doReverify">按最新事实核实</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onActivated, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adApi } from '../api'

const mro = inject('mro')

const tab = ref('directives')
const models = ['B737', 'A320', 'A330', 'ARJ21']
const statusOptions = [
  { code: 'NOT_DUE', name: '未到期' },
  { code: 'DUE_SOON', name: '临期' },
  { code: 'OVERDUE', name: '超期' },
  { code: 'PENDING_VERIFY', name: '待核实' },
  { code: 'NOT_APPLICABLE', name: '不适用' },
  { code: 'COMPLIED', name: '已落实' }
]

const ov = ref({})
const directives = ref([])
const compliances = ref([])
const batches = ref([])
const events = ref([])
const filterRevisions = ref([])

const filter = ref({ directiveId: null, revisionId: null, model: '', tailNo: '', status: '', includeHistory: false })

// ---------- 指令 / 版本 ----------
const activeDirective = ref(null)
const versionDrawer = ref(false)
const allRevisions = ref([])

const revDialog = ref({
  visible: false, mode: 'create', title: '', directiveId: null, revId: null, liveIssue: '',
  form: blankRevForm()
})

function blankRevForm() {
  return {
    adNo: '', title: '', issuer: 'CAAC', revisionNo: '0', effectiveDate: todayStr(),
    dueDate: null, dueFh: null, dueCycles: null,
    warnDays: null, warnFh: null, warnCycles: null,
    demoFailTails: '', scopes: [{ model: 'B737', tailLower: '', tailUpper: '' }]
  }
}

// ---------- 落实 ----------
const complyDialog = ref({
  visible: false, row: null, operator: '', cardIds: [], cards: [],
  refNo: '', note: '', file: null, fileList: [], saving: false
})
const detailDrawer = ref(false)
const detail = ref(null)
const detailEvents = ref([])
const addEvidenceDialog = ref(false)
const addEv = ref({ operator: '', refNo: '', note: '' })
const reverifyDialog = ref(false)
const reverify = ref({ operator: '', note: '' })

// ---------- 批量 ----------
const expandedBatch = ref(null)
const batchStarting = ref(false)
let batchTimer = null

function todayStr() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
function fmtDate(v) {
  return v ? String(v).substring(0, 10) : null
}
function fmtTime(v) {
  return v ? String(v).replace('T', ' ').substring(0, 16) : ''
}
function fmtNum(v) {
  if (v === null || v === undefined) return '—'
  return Number(v).toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 2 })
}
function d(id) {
  return directives.value.find((x) => x.id === id) || { adNo: '' }
}
function statusTag(code) {
  return { NOT_DUE: 'info', DUE_SOON: 'warning', OVERDUE: 'danger', PENDING_VERIFY: 'warning', NOT_APPLICABLE: 'info', COMPLIED: 'success' }[code] || 'info'
}
function marginClass(v) {
  if (v === null || v === undefined) return 'muted'
  if (v < 0) return 'ad-bad'
  return ''
}
function marginNum(v) {
  if (v === null || v === undefined) return 'muted'
  if (Number(v) <= 0) return 'ad-bad'
  return ''
}
function pct(job) {
  if (!job.totalCount) return 0
  return Math.round(((job.successCount + job.failedCount) / job.totalCount) * 100)
}
function eventColor(type) {
  if (type === 'COMPLY' || type === 'BATCH_DONE' || type === 'REVERIFY') return 'success'
  if (type === 'CARD_CHANGED' || type === 'EVIDENCE_MISSING' || type === 'BATCH_FAIL') return 'danger'
  if (type === 'STATUS_CHANGE' || type === 'OVERDUE') return 'warning'
  return 'primary'
}
function downloadUrl(id) {
  return `/api/ad/evidence/${id}/download`
}

// ================= 数据加载 =================
async function loadOverview() {
  ov.value = await adApi.overview()
}
async function loadDirectives() {
  directives.value = await adApi.directives()
}
async function loadCompliance() {
  const params = {}
  if (filter.value.directiveId) params.directiveId = filter.value.directiveId
  if (filter.value.revisionId) params.revisionId = filter.value.revisionId
  if (filter.value.model) params.model = filter.value.model
  if (filter.value.tailNo) params.tailNo = filter.value.tailNo
  if (filter.value.status) params.status = filter.value.status
  params.includeHistory = filter.value.includeHistory
  compliances.value = await adApi.compliances(params)
}
async function onDirectiveFilter(id) {
  filter.value.revisionId = null
  filterRevisions.value = id ? await adApi.revisions(id) : []
  await loadCompliance()
}
async function loadBatches() {
  const prev = expandedBatch.value
  batches.value = await adApi.batches()
  const open = batches.value.find((b) => b.id === prev)
  if (open && open.status === 'DONE' && open.failedCount === 0) {
    expandedBatch.value = null
  }
}
async function loadEvents() {
  events.value = await adApi.events(
    eventFilter.value.directiveId ? { directiveId: eventFilter.value.directiveId } : {}
  )
}
const eventFilter = ref({ directiveId: null })

async function onTab(name) {
  if (name === 'compliance') await loadCompliance()
  if (name === 'batch') startBatchPolling()
  else stopBatchPolling()
  if (name === 'events') await loadEvents()
}

function startBatchPolling() {
  stopBatchPolling()
  loadBatches()
  batchTimer = setInterval(loadBatches, 1500)
}
function stopBatchPolling() {
  if (batchTimer) clearInterval(batchTimer)
  batchTimer = null
}
const visibleItemJobs = computed(() => batches.value)

function goCompliance(row) {
  tab.value = 'compliance'
  filter.value.directiveId = row.id
  filter.value.revisionId = row.currentRevId
  onDirectiveFilter(row.id)
}

// ================= 发布/修订/编辑 =================
function openCreate() {
  revDialog.value = { visible: true, mode: 'create', title: '发布适航指令', directiveId: null, revId: null,
    liveIssue: '', form: blankRevForm() }
}
async function openRevise(row) {
  const revs = await adApi.revisions(row.id)
  const cur = revs.find((r) => r.status === 'ACTIVE') || revs[revs.length - 1]
  const f = formFromRevision(cur)
  f.revisionNo = String(Number(cur.revisionNo) + 1)
  f.demoFailTails = ''
  revDialog.value = { visible: true, mode: 'revise', title: `${row.adNo} 新增修订版（旧版冻结）`,
    directiveId: row.id, revId: null, liveIssue: '', form: f }
}
async function openEdit(row, specificRev) {
  let rev
  if (specificRev) {
    rev = specificRev
  } else {
    const revs = await adApi.revisions(row.id)
    rev = revs.find((r) => r.status === 'ACTIVE')
  }
  if (!rev) return ElMessage.warning('该指令没有当前生效版本')
  if (rev.status === 'SUPERSEDED') return ElMessage.warning('历史版本只读，不能编辑')
  revDialog.value = { visible: true, mode: 'edit', title: `编辑 ${row.adNo} R${rev.revisionNo} 范围与阈值`,
    directiveId: row.id, revId: rev.id, liveIssue: rev.configIssue || '', form: formFromRevision(rev) }
}
function formFromRevision(r) {
  return {
    adNo: '', title: '', issuer: 'CAAC', revisionNo: r.revisionNo, effectiveDate: fmtDate(r.effectiveDate),
    dueDate: r.dueDate ? fmtDate(r.dueDate) : null,
    dueFh: r.dueFh ?? null, dueCycles: r.dueCycles ?? null,
    warnDays: r.warnDays ?? null, warnFh: r.warnFh ?? null, warnCycles: r.warnCycles ?? null,
    demoFailTails: r.demoFailTails || '',
    scopes: (r.scopes || []).map((s) => ({ model: s.model, tailLower: s.tailLower || '', tailUpper: s.tailUpper || '' }))
  }
}

function cleanScopes(scopes) {
  return scopes
    .filter((s) => s.model)
    .map((s) => ({
      model: s.model,
      tailLower: s.tailLower && s.tailLower.trim() ? s.tailLower.trim() : null,
      tailUpper: s.tailUpper && s.tailUpper.trim() ? s.tailUpper.trim() : null
    }))
}
function numOrNull(v) {
  return v === null || v === undefined || v === '' ? null : Number(v)
}

function clientConfigIssue(f) {
  const issues = []
  if (!f.dueDate && (f.dueFh === null || f.dueFh === undefined) && (f.dueCycles === null || f.dueCycles === undefined)) {
    issues.push('至少需要一类截止阈值')
  }
  if (f.dueFh !== null && f.dueFh !== undefined && f.warnFh !== null && f.warnFh !== undefined && Number(f.warnFh) >= Number(f.dueFh)) {
    issues.push(`飞行小时临期窗口 ${f.warnFh} ≥ 阈值 ${f.dueFh}（配置矛盾，命中飞机将进待核实）`)
  }
  if (f.dueCycles !== null && f.dueCycles !== undefined && f.warnCycles !== null && f.warnCycles !== undefined && Number(f.warnCycles) >= Number(f.dueCycles)) {
    issues.push(`起落次数临期窗口 ${f.warnCycles} ≥ 阈值 ${f.dueCycles}（配置矛盾）`)
  }
  if (!f.dueDate && f.warnDays !== null && f.warnDays !== undefined) issues.push('配了提前天数但没有日期阈值')
  if ((f.dueFh === null || f.dueFh === undefined) && f.warnFh !== null && f.warnFh !== undefined) issues.push('配了提前飞行小时但没有 FH 阈值')
  if ((f.dueCycles === null || f.dueCycles === undefined) && f.warnCycles !== null && f.warnCycles !== undefined) issues.push('配了提前起落次数但没有起落阈值')
  return issues.join('；')
}

async function saveRevision() {
  const f = revDialog.value.form
  const scopes = cleanScopes(f.scopes)
  if (!scopes.length) return ElMessage.warning('适用范围至少配置一行机型')
  if (f.mode === 'create' && (!f.adNo || !f.title)) return ElMessage.warning('编号、标题必填')
  const payload = {
    effectiveDate: f.effectiveDate || todayStr(),
    dueDate: f.dueDate || null,
    dueFh: numOrNull(f.dueFh),
    dueCycles: numOrNull(f.dueCycles),
    warnDays: numOrNull(f.warnDays),
    warnFh: numOrNull(f.warnFh),
    warnCycles: numOrNull(f.warnCycles),
    scopes
  }
  try {
    if (f.mode === 'create') {
      await adApi.createDirective({
        adNo: f.adNo, title: f.title, issuer: f.issuer || 'CAAC', revisionNo: f.revisionNo || '0',
        ...payload, demoFailTails: f.demoFailTails || null
      })
      ElMessage.success('指令已发布，覆盖机队正在后台重算')
    } else if (f.mode === 'revise') {
      await adApi.revise(revDialog.value.directiveId, { revisionNo: f.revisionNo, ...payload })
      ElMessage.success('新修订版已发布，旧版冻结，机队正在后台重算')
    } else {
      await adApi.editRevision(revDialog.value.revId, payload)
      ElMessage.success('范围/阈值已更新，覆盖机队正在后台重算')
    }
    revDialog.value.visible = false
    await loadDirectives()
    await loadComplianceSafe()
    tab.value = 'batch'
    startBatchPolling()
  } catch (_) { /* 拦截器已提示 */ }
}
async function loadComplianceSafe() {
  try { await loadCompliance() } catch (_) { /* ignore */ }
}

// ================= 版本抽屉 =================
async function openVersions(row) {
  activeDirective.value = row
  allRevisions.value = await adApi.revisions(row.id)
  versionDrawer.value = true
}
function viewBatchOf(rev) {
  versionDrawer.value = false
  tab.value = 'batch'
  startBatchPolling()
}

// ================= 登记落实 =================
async function openComply(row) {
  if (row.revStatus === 'SUPERSEDED') return ElMessage.warning('该结果属于已替代的历史版本，不能登记落实')
  let cards = []
  try {
    cards = await adApi.closedCards(row.aircraftId)
  } catch (_) { cards = [] }
  complyDialog.value = {
    visible: true, row, operator: mro && mro.operator ? mro.operator : '罗建军',
    cardIds: [], cards, refNo: '', note: '', file: null, fileList: [], saving: false
  }
}
function onPickFile(uploadFile) {
  complyDialog.value.file = uploadFile.raw
  complyDialog.value.fileList = [uploadFile]
}
async function doComply() {
  const c = complyDialog.value
  if (!c.operator || !c.operator.trim()) return ElMessage.warning('请填写落实操作人')
  if (!c.cardIds.length) return ElMessage.warning('至少关联一张同机已关闭工卡')
  if (!c.refNo && !c.file) return ElMessage.warning('请登记凭据编号或上传证据文件（至少一种）')
  const fd = new FormData()
  fd.append('revisionId', c.row.revisionId)
  fd.append('aircraftId', c.row.aircraftId)
  fd.append('operator', c.operator)
  c.cardIds.forEach((id) => fd.append('cardIds', id))
  if (c.refNo) fd.append('evidenceRefNo', c.refNo)
  if (c.note) fd.append('evidenceNote', c.note)
  if (c.file) fd.append('file', c.file)
  c.saving = true
  try {
    await adApi.comply(fd)
    ElMessage.success('落实结果已登记')
    c.visible = false
    await loadCompliance()
    await loadOverview()
    if (mro.refreshAdOverview) mro.refreshAdOverview()
  } catch (_) { /* 拦截器已提示 */ } finally {
    c.saving = false
  }
}

// ================= 详情 / 证据 / 核实 =================
async function openDetail(row) {
  detail.value = await adApi.compliance(row.id)
  detailEvents.value = await adApi.events({ revisionId: row.revisionId, aircraftId: row.aircraftId })
  detailDrawer.value = true
}
async function refreshDetail() {
  if (!detail.value) return
  detail.value = await adApi.compliance(detail.value.id)
  detailEvents.value = await adApi.events({ revisionId: detail.value.revisionId, aircraftId: detail.value.aircraftId })
  await loadCompliance()
  await loadOverview()
}
async function markMissing(row) {
  try {
    const { value } = await ElMessageBox.prompt('标记证据缺失后，原“已落实”将自动转为待核实（证据记录保留不删）。原因：',
      '标记证据缺失', { inputValue: '', confirmButtonText: '标记缺失', cancelButtonText: '取消' })
    await adApi.markEvidenceMissing(row.id, { operator: '管理员', reason: value || '证据缺失' })
    ElMessage.success('已标记缺失，结果已转为待核实')
    await refreshDetail()
  } catch (e) { if (e !== 'cancel') { /* handled */ } }
}
function onAddFile(uploadFile) {
  ElMessageBox.prompt('请输入登记人姓名', '上传证据', { confirmButtonText: '上传', cancelButtonText: '取消', inputValue: '罗建军' })
    .then(async ({ value }) => {
      const fd = new FormData()
      fd.append('operator', value || '管理员')
      fd.append('file', uploadFile.raw)
      await adApi.uploadEvidence(detail.value.id, fd)
      ElMessage.success('证据文件已上传')
      await refreshDetail()
    }).catch(() => {})
}
async function doAddEvidence() {
  if (!addEv.value.operator || !addEv.value.refNo) return ElMessage.warning('登记人与凭据编号必填')
  await adApi.addEvidence(detail.value.id, { ...addEv.value })
  ElMessage.success('证据已登记')
  addEvidenceDialog.value = false
  addEv.value = { operator: '', refNo: '', note: '' }
  await refreshDetail()
}
async function doReverify() {
  if (!reverify.value.operator) return ElMessage.warning('请填写核实人')
  await adApi.reverify(detail.value.id, { ...reverify.value })
  ElMessage.success('已按最新事实完成核实')
  reverifyDialog.value = false
  reverify.value = { operator: '', note: '' }
  await refreshDetail()
}

// ================= 批量 =================
function toggleItems(job) {
  expandedBatch.value = expandedBatch.value === job.id ? null : job.id
}
async function retryBatch(job) {
  job._retrying = true
  try {
    await adApi.retryBatch(job.id, '管理员')
    ElMessage.success('失败项已重新入队，已成功飞机不重放')
    expandedBatch.value = job.id
    startBatchPolling()
  } catch (_) { /* 拦截器提示 */ } finally {
    job._retrying = false
  }
}
async function manualRecompute() {
  const revId = filter.value.revisionId
  if (!revId) return ElMessage.info('请先在落实清单选择一个修订版（或从指令目录进入）')
  batchStarting.value = true
  try {
    await adApi.recompute(revId, '管理员')
    ElMessage.success('批量重算已开始')
    tab.value = 'batch'
    startBatchPolling()
  } catch (_) { /* handled */ } finally {
    batchStarting.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadOverview(), loadDirectives()])
  await loadCompliance()
})
onActivated(() => { loadOverview() })
</script>

<style scoped>
.ad-tabs {
  margin-top: 10px;
}
.ad-bad {
  color: var(--mro-danger);
  font-weight: 700;
}
.ad-warn {
  color: var(--mro-warn);
}
.ad-ok {
  color: var(--mro-ok);
  font-weight: 700;
}
.ad-items {
  margin: 10px 0 16px;
  border: 1px dashed var(--mro-border);
  border-radius: 8px;
  padding: 10px;
}
.ad-fail-box {
  background: #fdecea;
  border: 1px solid #f5c6cb;
  color: var(--mro-danger);
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 12px;
  margin-bottom: 8px;
}
.ad-scope-block {
  margin-bottom: 14px;
  padding: 8px 10px;
  background: #f7fafd;
  border: 1px solid var(--mro-border);
  border-radius: 8px;
}
.ad-scope-head {
  font-weight: 700;
  color: var(--mro-primary-dark);
  margin-bottom: 6px;
  display: flex;
  gap: 8px;
  align-items: center;
}
.ad-scope-tag {
  margin: 2px 6px 2px 0;
}
</style>

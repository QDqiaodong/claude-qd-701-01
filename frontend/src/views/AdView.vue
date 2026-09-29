<template>
  <div class="ad-page">
    <!-- 顶部计数 -->
    <section class="panel">
      <div class="ad-kpis">
        <div class="kpi"><b>{{ ov.directiveCount || 0 }}</b><span>指令总数</span></div>
        <div class="kpi kpi-danger"><b>{{ ov.overdue || 0 }}</b><span>超期</span></div>
        <div class="kpi kpi-warn"><b>{{ ov.nearDue || 0 }}</b><span>临期</span></div>
        <div class="kpi kpi-info"><b>{{ ov.pending || 0 }}</b><span>待核实</span></div>
        <div class="kpi kpi-ok"><b>{{ ov.complied || 0 }}</b><span>已落实</span></div>
        <div class="kpi"><b :class="{ 'is-alert': (ov.runningJobCount || 0) > 0 }">{{ ov.runningJobCount || 0 }}</b><span>重算进行中</span></div>
        <div style="margin-left:auto">
          <el-button size="small" @click="reloadAll">刷新</el-button>
          <el-button size="small" type="primary" @click="openCreateDirective">登记新指令</el-button>
        </div>
      </div>
    </section>

    <el-tabs v-model="tab" class="ad-tabs">
      <!-- ========== 指令目录 ========== -->
      <el-tab-pane label="指令目录与修订" name="directives">
        <section class="panel">
          <el-table :data="directives" border stripe size="small">
            <el-table-column prop="adNo" label="指令编号" width="140" />
            <el-table-column prop="authority" label="发布机构" width="120" />
            <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
            <el-table-column label="最新版" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="revTagType(row.revisionStatus)">{{ row.latestRevision }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="适用机型" width="130">
              <template #default="{ row }">{{ row.applicableModels || '—' }}</template>
            </el-table-column>
            <el-table-column label="机号范围" width="130">
              <template #default="{ row }">
                <span v-if="row.tailRangeFrom || row.tailRangeTo">
                  {{ row.tailRangeFrom || '？' }} ~ {{ row.tailRangeTo || '？' }}
                  <el-tag v-if="oneBoundOnly(row)" size="small" type="warning">边界不全</el-tag>
                </span>
                <span v-else>不限</span>
              </template>
            </el-table-column>
            <el-table-column label="截止阈值（日期/FH/起落）" width="220">
              <template #default="{ row }">
                <span>{{ row.dueDate || '—' }} / {{ row.dueFh != null ? row.dueFh : '—' }} / {{ row.dueCycles != null ? row.dueCycles : '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="生效日期" width="110">
              <template #default="{ row }">{{ row.effectiveDate }}</template>
            </el-table-column>
            <el-table-column label="版本数" width="70" prop="revisionCount" />
            <el-table-column label="操作" width="260" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="viewCompliance(row)">落实清单</el-button>
                <el-button link @click="openRevisions(row)">修订/历史</el-button>
                <el-button link type="success" @click="recalcRevision(row)">重算</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <!-- ========== 落实清单 ========== -->
      <el-tab-pane label="飞机落实清单" name="compliance">
        <section class="panel">
          <div class="ad-filters">
            <el-select v-model="filter.directiveId" placeholder="按指令筛选" clearable filterable
                       style="width: 210px" @change="onDirectiveFilter">
              <el-option v-for="d in directives" :key="d.id"
                         :label="d.adNo + ' ' + d.latestRevision" :value="d.id" />
            </el-select>
            <el-select v-model="filter.revisionId" placeholder="按修订版筛选（含历史版本）" clearable filterable style="width: 230px">
              <el-option v-for="r in revisionOptions" :key="r.id"
                         :label="r.adNo + ' ' + r.revisionNo + '（' + r.statusName + '）'" :value="r.id" />
            </el-select>
            <el-select v-model="filter.model" placeholder="机型" clearable style="width: 110px">
              <el-option v-for="m in ['B737','A320','A330','ARJ21']" :key="m" :label="m" :value="m" />
            </el-select>
            <el-input v-model="filter.tailNo" placeholder="机号关键字，如 6032" clearable style="width: 160px" />
            <el-select v-model="filter.status" placeholder="状态" clearable style="width: 120px">
              <el-option v-for="s in statusOptions" :key="s.code" :label="s.name" :value="s.code" />
            </el-select>
            <el-checkbox v-model="filter.includeSuperseded">显示已取代旧版本结果</el-checkbox>
            <el-button type="primary" size="small" @click="loadCompliance">查询</el-button>
            <el-button size="small" @click="resetFilter">重置</el-button>
            <div style="margin-left:auto">
              <el-button size="small" type="warning" @click="fleetRecalc">全机队批量重算</el-button>
            </div>
          </div>

          <el-table :data="compliance" border stripe size="small" :row-class-name="complianceRowClass">
            <el-table-column prop="adNo" label="指令" width="130" />
            <el-table-column prop="revisionNo" label="版本" width="70" />
            <el-table-column prop="tailNo" label="机号" width="90" />
            <el-table-column prop="model" label="机型" width="70" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="statusTagType(row.status)">{{ row.statusName }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="最早限制" width="90">
              <template #default="{ row }">{{ row.limitingDimName || '—' }}</template>
            </el-table-column>
            <el-table-column label="阈值余量（天 / FH / 起落）" min-width="230">
              <template #default="{ row }">
                <span :class="remainClass(row.daysToDue)">{{ fmt(row.daysToDue, '天') }}</span>
                /
                <span :class="remainClass(row.fhRemaining)">{{ fmt(row.fhRemaining, 'h') }}</span>
                /
                <span :class="remainClass(row.cycleRemaining)">{{ fmt(row.cycleRemaining, '次') }}</span>
                <div v-if="row.fhMissing || row.cyclesMissing" class="ad-subwarn">
                  飞机累计值：FH {{ row.fhMissing ? '缺失' : row.aircraftFh }}，起落 {{ row.cyclesMissing ? '缺失' : row.aircraftCycles }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="待核实原因" min-width="220">
              <template #default="{ row }">
                <span class="ad-pending-reason">{{ row.pendingReasons || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="凭证" width="110">
              <template #default="{ row }">
                <el-tooltip content="有效工卡关联数 / 有效证据数">
                  <span>工卡 {{ row.validLinkCount }} · 证据 {{ row.validEvidenceCount }}</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="计算 tick / 时间" width="150">
              <template #default="{ row }">
                <div>#{{ row.calcTick }}</div>
                <div class="ad-sub">{{ fmtTime(row.calcAt) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openDetail(row)">详情/落实</el-button>
                <el-button link @click="manualRecompute(row)">复核</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <!-- ========== 批量重算 ========== -->
      <el-tab-pane label="批量重算作业" name="jobs">
        <section class="panel">
          <div class="panel-head" style="margin-bottom:8px">
            <span class="panel-sub">批量任务后台执行、逐机独立事务，不占住普通查询与飞行登记；进度全部来自服务端，刷新页面一致。</span>
            <el-button size="small" type="warning" @click="fleetRecalc">新建全机队重算</el-button>
          </div>
          <el-table :data="jobs" border stripe size="small">
            <el-table-column prop="id" label="#" width="60" />
            <el-table-column label="范围" width="120">
              <template #default="{ row }">{{ row.scopeTypeName }}</template>
            </el-table-column>
            <el-table-column label="指令/机号" width="150">
              <template #default="{ row }">{{ row.adNo || '全部指令' }} {{ row.revisionNo || '' }} {{ row.tailNo || '' }}</template>
            </el-table-column>
            <el-table-column prop="triggerReason" label="原因" min-width="200" show-overflow-tooltip />
            <el-table-column label="状态" width="130">
              <template #default="{ row }">
                <el-tag size="small" :type="jobTagType(row.status)">{{ row.statusName }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="进度" width="240">
              <template #default="{ row }">
                <el-progress :percentage="row.progressPercent" :status="row.failedCount > 0 ? 'exception' : (row.progressPercent === 100 ? 'success' : '')" />
                <div class="ad-sub">
                  共 {{ row.totalCount }} · 成功 {{ row.successCount }} · 不适用 {{ row.naCount }} · 失败 {{ row.failedCount }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="160">
              <template #default="{ row }">
                <div class="ad-sub">{{ fmtTime(row.startedAt) }}</div>
                <div class="ad-sub" v-if="row.finishedAt">完成 {{ fmtTime(row.finishedAt) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openJob(row)">查看明细/失败原因</el-button>
                <el-button v-if="row.status === 'PARTIAL_FAILED'" link type="success" @click="resumeJob(row)">续跑失败项</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>
    </el-tabs>

    <!-- ========== 修订历史抽屉 ========== -->
    <el-drawer v-model="revDrawer" size="62%" :title="(activeDirective.adNo || '') + ' 修订版与适用范围编辑'">
      <div v-if="activeDirective.id">
        <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px"
                  title="生效版不可直接改：改范围/阈值会创建新修订版草稿，发布后旧版结果与证据冻结保留，按新版重算覆盖机队。" />
        <el-button type="primary" size="small" @click="openNewRevision(activeDirective)" style="margin-bottom:10px">
          基于当前版创建新修订版草稿
        </el-button>
        <el-timeline>
          <el-timeline-item v-for="r in revisionList" :key="r.id" :timestamp="r.publishedAt ? ('生效 ' + fmtTime(r.publishedAt)) : '草稿未发布'"
                            :type="r.status === 'EFFECTIVE' ? 'success' : (r.status === 'DRAFT' ? 'primary' : 'info')">
            <el-card shadow="never">
              <div class="rev-head">
                <b>{{ r.adNo }} {{ r.revisionNo }}</b>
                <el-tag size="small" :type="revTagType(r.status)">{{ r.statusName }}</el-tag>
                <span class="ad-sub">{{ r.title }}</span>
              </div>
              <el-descriptions :column="3" size="small" border style="margin-top:6px">
                <el-descriptions-item label="生效日期">{{ r.effectiveDate }}</el-descriptions-item>
                <el-descriptions-item label="适用机型">{{ r.applicableModels }}</el-descriptions-item>
                <el-descriptions-item label="机号范围">
                  {{ r.tailRangeFrom || '不限' }} ~ {{ r.tailRangeTo || '不限' }}
                </el-descriptions-item>
                <el-descriptions-item label="截止日期">{{ r.dueDate || '—' }}</el-descriptions-item>
                <el-descriptions-item label="截止 FH">{{ r.dueFh != null ? r.dueFh : '—' }}</el-descriptions-item>
                <el-descriptions-item label="截止起落">{{ r.dueCycles != null ? r.dueCycles : '—' }}</el-descriptions-item>
                <el-descriptions-item label="临期提前(天)">{{ r.warnDays }}</el-descriptions-item>
                <el-descriptions-item label="临期剩余 FH">{{ r.warnFh }}</el-descriptions-item>
                <el-descriptions-item label="临期剩余起落">{{ r.warnCycles }}</el-descriptions-item>
              </el-descriptions>
              <div v-if="r.revisionNote" class="ad-sub" style="margin-top:6px">修订说明：{{ r.revisionNote }}</div>
              <div style="margin-top:8px">
                <el-button v-if="r.status === 'DRAFT'" size="small" type="primary" @click="openDraftEdit(r)">编辑范围/阈值</el-button>
                <el-button v-if="r.status === 'DRAFT'" size="small" type="success" @click="publishRevision(r)">发布并重算</el-button>
                <el-button size="small" @click="viewRevisionCompliance(r)">查看该版落实清单</el-button>
                <el-button v-if="r.status !== 'DRAFT'" size="small" @click="recalcRevisionId(r.id, r.adNo)">按此版重算</el-button>
              </div>
            </el-card>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- ========== 清单详情抽屉：落实/证据/时间线 ========== -->
    <el-drawer v-model="detailDrawer" size="70%" :title="detailTitle">
      <div v-if="detail">
        <el-descriptions :column="4" size="small" border>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="statusTagType(detail.compliance.status)">{{ detail.compliance.statusName }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="最早限制">{{ detail.compliance.limitingDimName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="余量(天/FH/起落)">
            {{ detail.compliance.daysToDue }} / {{ detail.compliance.fhRemaining }} / {{ detail.compliance.cycleRemaining }}
          </el-descriptions-item>
          <el-descriptions-item label="计算 tick">#{{ detail.compliance.calcTick }} · {{ fmtTime(detail.compliance.calcAt) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.compliance.pendingReasons" :span="4">
            <span class="ad-pending-reason">{{ detail.compliance.pendingReasons }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="detail-actions">
          <el-button type="success" size="small" @click="openComply">登记落实（关联关闭工卡+证据）</el-button>
          <el-button size="small" @click="openAddEvidence">追加登记证据</el-button>
          <el-button size="small" @click="uploadRef.click()">上传证据文件</el-button>
          <input ref="uploadRef" type="file" style="display:none" @change="onUploadFile" />
          <el-button size="small" @click="manualRecompute(detail.compliance)">服务端复核</el-button>
        </div>

        <h4>工卡关联（历史不删除；取消/重开/改挂后标记失效）</h4>
        <el-table :data="detail.links" size="small" border>
          <el-table-column prop="cardNo" label="工卡号" width="140" />
          <el-table-column label="关联时机号" width="110">
            <template #default="{ row }">{{ row.linkedTailNo }}</template>
          </el-table-column>
          <el-table-column label="当前机号" width="110">
            <template #default="{ row }">
              {{ row.currentTailNo || '已不存在' }}
              <el-tag v-if="row.currentTailNo && row.currentTailNo !== row.linkedTailNo" size="small" type="danger">改挂</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="当前状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.cardClosed ? 'success' : 'warning'">{{ row.currentCardStatusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="linkedBy" label="关联人" width="90" />
          <el-table-column label="关联时间" width="150">
            <template #default="{ row }">{{ fmtTime(row.linkedAt) }}</template>
          </el-table-column>
          <el-table-column label="有效性" min-width="200">
            <template #default="{ row }">
              <el-tag v-if="row.valid" size="small" type="success">有效</el-tag>
              <div v-else>
                <el-tag size="small" type="danger">已失效</el-tag>
                <span class="ad-sub">{{ row.invalidReason }}</span>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <h4>落实证据（作废记录保留）</h4>
        <el-table :data="detail.evidence" size="small" border>
          <el-table-column prop="title" label="标题" min-width="180" />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ row.evidenceTypeName }}</template>
          </el-table-column>
          <el-table-column label="引用/文件" min-width="180">
            <template #default="{ row }">
              <a v-if="row.evidenceType === 'FILE'" :href="adApi.downloadEvidenceUrl(row.id)" target="_blank">{{ row.fileName }}</a>
              <span v-else>{{ row.evidenceRef }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="registeredBy" label="登记人" width="90" />
          <el-table-column label="登记时间" width="150">
            <template #default="{ row }">{{ fmtTime(row.registeredAt) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="200">
            <template #default="{ row }">
              <el-tag v-if="!row.voided" size="small" type="success">有效</el-tag>
              <div v-else>
                <el-tag size="small" type="danger">已作废</el-tag>
                <span class="ad-sub">{{ row.voidReason }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button v-if="!row.voided" link type="danger" @click="voidEvidence(row)">作废</el-button>
            </template>
          </el-table-column>
        </el-table>

        <h4>状态变化 / 证据记录（append-only）</h4>
        <el-timeline>
          <el-timeline-item v-for="e in detail.events" :key="e.id" :timestamp="fmtTime(e.eventAt) + ' · ' + e.operator" placement="top">
            <el-tag size="small" style="margin-right:6px">{{ e.eventName }}</el-tag>
            <span v-if="e.fromStatusName || e.toStatusName" class="ad-sub">
              {{ e.fromStatusName || '—' }} → {{ e.toStatusName || '—' }}：
            </span>
            {{ e.detail }}
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- ========== 作业明细抽屉 ========== -->
    <el-drawer v-model="jobDrawer" size="60%" title="批量重算作业明细">
      <div v-if="activeJob.id">
        <el-alert :title="`本次范围：${activeJob.scopeTypeName}；共 ${activeJob.totalCount} 项，成功 ${activeJob.successCount}，不适用 ${activeJob.naCount}，失败 ${activeJob.failedCount}`"
                  :type="activeJob.failedCount > 0 ? 'error' : 'success'" :closable="false" show-icon style="margin-bottom:10px" />
        <el-button v-if="activeJob.status === 'PARTIAL_FAILED'" type="success" size="small" @click="resumeJob(activeJob)" style="margin-bottom:8px">
          续跑失败项（不重复生成结果）
        </el-button>
        <el-table :data="jobItems" size="small" border>
          <el-table-column prop="tailNo" label="机号" width="100" />
          <el-table-column prop="model" label="机型" width="80" />
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="jobItemTag(row.status)">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="errorMessage" label="失败原因" min-width="260" show-overflow-tooltip />
          <el-table-column prop="attemptCount" label="尝试次数" width="80" />
          <el-table-column label="更新时间" width="160">
            <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <!-- ========== 新建指令 ========== -->
    <el-dialog v-model="createVisible" title="登记适航指令（含首版 R0）" width="760px">
      <revision-form :form="createForm" />
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button @click="submitCreate(false)">仅存草稿</el-button>
        <el-button type="primary" @click="submitCreate(true)">建档并发布 R0</el-button>
      </template>
    </el-dialog>

    <!-- ========== 新修订版草稿 ========== -->
    <el-dialog v-model="newRevVisible" title="创建新修订版草稿（发布前不影响现行判断）" width="760px">
      <revision-form :form="newRevForm" />
      <div style="margin-top:6px">
        <el-input v-model="newRevOperator" placeholder="操作人" style="width:200px" />
      </div>
      <template #footer>
        <el-button @click="newRevVisible = false">取消</el-button>
        <el-button type="primary" @click="submitNewRevision">创建草稿</el-button>
      </template>
    </el-dialog>

    <!-- ========== 编辑草稿 ========== -->
    <el-dialog v-model="draftVisible" title="编辑草稿适用范围与阈值" width="760px">
      <revision-form :form="draftForm" />
      <template #footer>
        <el-button @click="draftVisible = false">取消</el-button>
        <el-button type="primary" @click="submitDraft">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- ========== 登记落实 ========== -->
    <el-dialog v-model="complyVisible" title="登记落实（至少一张同机已关闭工卡 + 至少一条证据）" width="680px">
      <div v-if="detail">
        <el-alert type="info" :closable="false" show-icon
                  :title="`飞机 ${detail.compliance.tailNo} · ${detail.compliance.adNo} ${detail.compliance.revisionNo}`" style="margin-bottom:8px" />
        <el-form label-width="80px">
          <el-form-item label="操作人">
            <el-input v-model="complyForm.operator" style="width:200px" />
          </el-form-item>
          <el-form-item label="工卡">
            <el-select v-model="complyForm.workCardIds" multiple filterable placeholder="选择本机【已关闭】工卡（重复关联不会生成两条）" style="width:100%">
              <el-option v-for="c in closableCards" :key="c.id"
                         :label="c.cardNo + ' ' + c.title + '（' + c.statusName + '）'" :value="c.id"
                         :disabled="c.status !== 'CLOSED'" />
            </el-select>
          </el-form-item>
          <el-form-item label="证据条目">
            <div style="width:100%">
              <div v-for="(ev, i) in complyForm.evidences" :key="i" class="evidence-row">
                <el-input v-model="ev.title" placeholder="证据标题" style="width:220px" />
                <el-input v-model="ev.evidenceRef" placeholder="可追溯登记号/引用，如 ARC-20260929-01" style="width:300px" />
                <el-button link type="danger" @click="complyForm.evidences.splice(i, 1)">删除</el-button>
              </div>
              <el-button size="small" @click="complyForm.evidences.push({ title: '', evidenceRef: '' })">+ 增加登记证据</el-button>
              <span class="ad-sub" style="margin-left:10px">也可在落实后用「上传证据文件」补充，文件与登记证据合计至少一条</span>
            </div>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="complyVisible = false">取消</el-button>
        <el-button type="success" @click="submitComply">确认落实</el-button>
      </template>
    </el-dialog>

    <!-- ========== 追加登记证据 ========== -->
    <el-dialog v-model="evidenceVisible" title="登记落实证据" width="520px">
      <el-form label-width="80px">
        <el-form-item label="登记人"><el-input v-model="evidenceForm.operator" /></el-form-item>
        <el-form-item label="标题"><el-input v-model="evidenceForm.title" /></el-form-item>
        <el-form-item label="登记号"><el-input v-model="evidenceForm.evidenceRef" placeholder="可追溯编号/文件号/工单号" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="evidenceVisible = false">取消</el-button>
        <el-button type="primary" @click="submitEvidence">登记</el-button>
      </template>
    </el-dialog>

    <!-- ========== 作废原因 ========== -->
    <el-dialog v-model="voidVisible" title="作废证据（记录保留）" width="480px">
      <el-form label-width="80px">
        <el-form-item label="操作人"><el-input v-model="voidForm.operator" /></el-form-item>
        <el-form-item label="原因"><el-input v-model="voidForm.reason" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="voidVisible = false">取消</el-button>
        <el-button type="danger" @click="submitVoid">确认作废</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adApi, cardApi, aircraftApi } from '../api'
import RevisionForm from './AdRevisionForm.vue'

const statusOptions = [
  { code: 'NOT_DUE', name: '未到期' },
  { code: 'NEAR_DUE', name: '临期' },
  { code: 'OVERDUE', name: '超期' },
  { code: 'COMPLIED', name: '已落实' },
  { code: 'PENDING', name: '待核实' },
  { code: 'NOT_APPLICABLE', name: '不适用' }
]

const tab = ref('directives')
const ov = ref({})
const directives = ref([])
const compliance = ref([])
const jobs = ref([])
const aircraftList = ref([])
const allCards = ref([])

const filter = reactive({
  directiveId: null, revisionId: null, model: '', tailNo: '', status: '', includeSuperseded: false
})
const revisionOptions = ref([])
const revisionList = ref([])
const activeDirective = ref({})
const revDrawer = ref(false)
const detailDrawer = ref(false)
const detail = ref(null)
const jobDrawer = ref(false)
const activeJob = ref({})
const jobItems = ref([])

const createVisible = ref(false)
const newRevVisible = ref(false)
const draftVisible = ref(false)
const complyVisible = ref(false)
const evidenceVisible = ref(false)
const voidVisible = ref(false)
const createForm = ref(blankRevision())
const newRevForm = ref(blankRevision())
const newRevOperator = ref('')
const draftForm = ref(blankRevision())
const draftTarget = ref(null)
const complyForm = reactive({ operator: '', workCardIds: [], evidences: [{ title: '', evidenceRef: '' }] })
const evidenceForm = reactive({ operator: '', title: '', evidenceRef: '' })
const voidForm = reactive({ operator: '', reason: '' })
const voidTarget = ref(null)
const uploadRef = ref(null)
let pollTimer = null

function blankRevision() {
  return {
    adNo: '', authority: '', title: '',
    revision: {
      title: '', effectiveDate: '', applicableModels: [],
      tailRangeFrom: '', tailRangeTo: '',
      dueDate: null, dueFh: null, dueCycles: null,
      warnDays: 0, warnFh: 0, warnCycles: 0, revisionNote: ''
    }
  }
}

// 机号范围只填一边 = 边界不完整（后端会判待核实，前端只做提示）
function oneBoundOnly(row) {
  const hasFrom = !!(row.tailRangeFrom && String(row.tailRangeFrom).trim())
  const hasTo = !!(row.tailRangeTo && String(row.tailRangeTo).trim())
  return hasFrom !== hasTo
}

const detailTitle = computed(() => {
  if (!detail.value) return '清单详情'
  const c = detail.value.compliance
  return `${c.adNo} ${c.revisionNo} · ${c.tailNo}（${c.model}）`
})

const closableCards = computed(() => {
  if (!detail.value) return []
  const tail = detail.value.compliance.tailNo
  return allCards.value.filter((c) => c.tailNo === tail)
})

function statusTagType(s) {
  return { NOT_DUE: 'info', NEAR_DUE: 'warning', OVERDUE: 'danger', COMPLIED: 'success',
           PENDING: 'warning', NOT_APPLICABLE: '' }[s] || 'info'
}
function revTagType(s) {
  return { EFFECTIVE: 'success', DRAFT: 'warning', SUPERSEDED: 'info' }[s] || 'info'
}
function jobTagType(s) {
  return { RUNNING: 'warning', DONE: 'success', PARTIAL_FAILED: 'danger', DONE_WITH_SKIP: 'success' }[s] || 'info'
}
function jobItemTag(s) {
  return { SUCCESS: 'success', FAILED: 'danger', PENDING: 'info', NOT_APPLICABLE: '' }[s] || 'info'
}
function fmt(v, unit) {
  if (v === null || v === undefined) return '—'
  return `${v}${unit || ''}`
}
function fmtTime(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
}
function remainClass(v) {
  if (v === null || v === undefined) return ''
  if (v < 0) return 'rem-over'
  if (v === 0) return 'rem-near'
  return ''
}
function complianceRowClass({ row }) {
  if (row.status === 'OVERDUE') return 'row-overdue'
  if (row.status === 'PENDING') return 'row-pending'
  return ''
}

async function reloadAll() {
  await Promise.all([loadOverview(), loadDirectives(), loadCompliance(), loadJobs(), loadBase()])
}
async function loadOverview() { ov.value = await adApi.overview() }
async function loadDirectives() { directives.value = await adApi.directives() }
async function loadBase() {
  aircraftList.value = await aircraftApi.list()
  allCards.value = await cardApi.list()
}
async function loadCompliance() {
  const params = {}
  if (filter.revisionId) params.revisionId = filter.revisionId
  else if (filter.directiveId) params.directiveId = filter.directiveId
  if (filter.model) params.model = filter.model
  if (filter.tailNo) params.tailNo = filter.tailNo
  if (filter.status) params.status = filter.status
  params.includeSuperseded = filter.includeSuperseded
  compliance.value = await adApi.compliance(params)
}
async function loadJobs() { jobs.value = await adApi.jobs() }

function resetFilter() {
  filter.directiveId = null; filter.revisionId = null; filter.model = ''
  filter.tailNo = ''; filter.status = ''; filter.includeSuperseded = false
  revisionOptions.value = []
  loadCompliance()
}
async function onDirectiveFilter(id) {
  filter.revisionId = null
  revisionOptions.value = []
  if (id) {
    const revs = await adApi.revisions(id)
    revisionOptions.value = revs
  }
  loadCompliance()
}
function viewCompliance(row) {
  tab.value = 'compliance'
  filter.directiveId = row.id
  filter.revisionId = null
  onDirectiveFilter(row.id)
}
async function viewRevisionCompliance(r) {
  revDrawer.value = false
  tab.value = 'compliance'
  filter.directiveId = null
  filter.revisionId = r.id
  filter.includeSuperseded = true
  await loadCompliance()
}

// ---------- 修订 ----------
async function openRevisions(row) {
  activeDirective.value = row
  revisionList.value = await adApi.revisions(row.id)
  revDrawer.value = true
}
function openCreateDirective() {
  createForm.value = blankRevision()
  createForm.value.revision.effectiveDate = new Date().toISOString().slice(0, 10)
  createVisible.value = true
}
async function submitCreate(publish) {
  const f = createForm.value
  if (!f.adNo || !f.authority || !f.title || !f.revision.title) {
    ElMessage.warning('请填写编号、机构、指令标题与版本标题')
    return
  }
  await adApi.createDirective({ ...f, publish })
  ElMessage.success(publish ? '指令已建档并发布，后台开始重算覆盖机队' : '草稿已保存')
  createVisible.value = false
  await loadDirectives()
}
function openNewRevision(row) {
  const current = revisionList.value.find((r) => r.status === 'EFFECTIVE') || revisionList.value.at(-1)
  newRevForm.value = {
    revision: {
      title: current.title, effectiveDate: new Date().toISOString().slice(0, 10),
      applicableModels: current.applicableModels.split(';'),
      tailRangeFrom: current.tailRangeFrom || '', tailRangeTo: current.tailRangeTo || '',
      dueDate: current.dueDate, dueFh: current.dueFh, dueCycles: current.dueCycles,
      warnDays: current.warnDays, warnFh: current.warnFh, warnCycles: current.warnCycles,
      revisionNote: ''
    }
  }
  newRevOperator.value = ''
  newRevVisible.value = true
}
async function submitNewRevision() {
  if (!newRevOperator.value) return ElMessage.warning('请填写操作人')
  await adApi.newRevision(activeDirective.value.id, { operator: newRevOperator.value, revision: newRevForm.value.revision })
  ElMessage.success('新修订版草稿已创建（旧版本保留，发布后才重算）')
  newRevVisible.value = false
  revisionList.value = await adApi.revisions(activeDirective.value.id)
  await loadDirectives()
}
function openDraftEdit(r) {
  draftTarget.value = r
  draftForm.value = {
    revision: {
      title: r.title, effectiveDate: r.effectiveDate,
      applicableModels: r.applicableModels.split(';'),
      tailRangeFrom: r.tailRangeFrom || '', tailRangeTo: r.tailRangeTo || '',
      dueDate: r.dueDate, dueFh: r.dueFh, dueCycles: r.dueCycles,
      warnDays: r.warnDays, warnFh: r.warnFh, warnCycles: r.warnCycles,
      revisionNote: r.revisionNote || ''
    }
  }
  draftVisible.value = true
}
async function submitDraft() {
  await adApi.updateDraft(draftTarget.value.id, { revision: draftForm.value.revision })
  ElMessage.success('草稿已保存')
  draftVisible.value = false
  revisionList.value = await adApi.revisions(activeDirective.value.id)
}
async function publishRevision(r) {
  const { value: operator } = await ElMessageBox.prompt('发布操作人', '发布修订版', {
    confirmButtonText: '发布并重算', cancelButtonText: '取消', inputValue: '适航工程师'
  }).catch(() => ({ value: null }))
  if (!operator) return
  await adApi.publish(r.id, operator)
  ElMessage.success('修订版已生效，后台重算覆盖机队；旧版本结果与证据保留')
  revisionList.value = await adApi.revisions(activeDirective.value.id)
  await Promise.all([loadDirectives(), loadJobs()])
}

// ---------- 重算 ----------
async function recalcRevision(row) {
  const rev = await pickRevision(row, '按该指令当前版重算覆盖机队')
  if (!rev) return
  await adApi.createJob({ scopeType: 'REVISION', revisionId: rev.id, operator: '适航工程师',
    reason: `${row.adNo} 范围/阈值变更后手动重算` })
  ElMessage.success('重算已开始（后台执行，不阻塞查询与飞行登记）')
  tab.value = 'jobs'
  await loadJobs()
}
async function recalcRevisionId(revId, adNo) {
  await adApi.createJob({ scopeType: 'REVISION', revisionId: revId, operator: '适航工程师',
    reason: `${adNo} 手动重算` })
  ElMessage.success('重算已开始')
  revDrawer.value = false
  tab.value = 'jobs'
  await loadJobs()
}
async function fleetRecalc() {
  const { value } = await ElMessageBox.prompt(
    '可输入要注入失败的飞机 id（逗号分隔，留空=全部成功；注入项首次失败，续跑成功）',
    '全机队批量重算', { confirmButtonText: '开始', cancelButtonText: '取消', inputValue: '' }).catch(() => ({ value: null }))
  if (value === undefined || value === null) return
  const ids = String(value).split(',').map((s) => Number(s.trim())).filter(Boolean)
  await adApi.createJob({ scopeType: 'FLEET', operator: '适航工程师',
    reason: '全机队批量重算（按全部生效修订版）', failInjectAircraftIds: ids.length ? ids : null })
  ElMessage.success('全机队重算已开始')
  tab.value = 'jobs'
  await loadJobs()
}
async function pickRevision(row, _reason) {
  const revs = await adApi.revisions(row.id)
  const effective = revs.filter((r) => r.status === 'EFFECTIVE')
  const options = effective.length ? effective : revs
  if (options.length === 1) return options[0]
  const { value } = await ElMessageBox.prompt('选择修订版 id（' + options.map((r) => `${r.id}:${r.revisionNo}`).join(', ') + '）',
    '选择修订版', { inputValue: options[0].id }).catch(() => ({ value: null }))
  return options.find((r) => r.id === Number(value)) || null
}
async function openJob(row) {
  activeJob.value = row
  jobItems.value = await adApi.jobItems(row.id)
  jobDrawer.value = true
}
async function resumeJob(row) {
  await adApi.resumeJob(row.id)
  ElMessage.success('失败项已开始续跑')
  await loadJobs()
  if (jobDrawer.value) {
    activeJob.value = (await adApi.job(row.id))
    jobItems.value = await adApi.jobItems(row.id)
  }
}

// ---------- 清单详情 / 落实 / 证据 ----------
async function openDetail(row) {
  await loadBase()
  detail.value = await adApi.complianceDetail(row.id)
  detailDrawer.value = true
}
async function refreshDetail() {
  if (!detail.value) return
  const id = detail.value.compliance.id
  detail.value = await adApi.complianceDetail(id)
}
async function manualRecompute(row) {
  const id = row.id || (detail.value && detail.value.compliance.id)
  await adApi.recompute(id, '适航工程师')
  ElMessage.success('已按服务端最新数据复核')
  await loadCompliance()
  await refreshDetail()
}
function openComply() {
  complyForm.operator = '适航工程师'
  complyForm.workCardIds = detail.value.links.filter((l) => l.valid).map((l) => l.workCardId)
  complyForm.evidences = [{ title: '', evidenceRef: '' }]
  complyVisible.value = true
}
async function submitComply() {
  const evidences = complyForm.evidences.filter((e) => e.title && e.evidenceRef)
  if (!complyForm.workCardIds.length) return ElMessage.warning('至少关联一张已关闭工卡')
  if (!evidences.length) return ElMessage.warning('请至少填写一条登记证据，或在落实后上传证据文件后再复核')
  await adApi.comply(detail.value.compliance.id, {
    operator: complyForm.operator, workCardIds: complyForm.workCardIds, evidences
  })
  ElMessage.success('落实已登记并复核成立')
  complyVisible.value = false
  await refreshDetail(); await loadCompliance(); await loadOverview()
}
function openAddEvidence() {
  evidenceForm.operator = '适航工程师'; evidenceForm.title = ''; evidenceForm.evidenceRef = ''
  evidenceVisible.value = true
}
async function submitEvidence() {
  if (!evidenceForm.title || !evidenceForm.evidenceRef) return ElMessage.warning('标题与登记号必填')
  await adApi.registerEvidence(detail.value.compliance.id, { ...evidenceForm })
  ElMessage.success('证据已登记')
  evidenceVisible.value = false
  await refreshDetail(); await loadCompliance()
}
async function onUploadFile(e) {
  const file = e.target.files[0]
  e.target.value = ''
  if (!file) return
  const fd = new FormData()
  fd.append('file', file)
  fd.append('operator', '适航工程师')
  await adApi.uploadEvidence(detail.value.compliance.id, fd)
  ElMessage.success('证据文件已上传')
  await refreshDetail(); await loadCompliance()
}
function voidEvidence(row) {
  voidTarget.value = row
  voidForm.operator = '质量经理'; voidForm.reason = ''
  voidVisible.value = true
}
async function submitVoid() {
  if (!voidForm.reason) return ElMessage.warning('作必须填写原因')
  await adApi.voidEvidence(voidTarget.value.id, { operator: voidForm.operator, reason: voidForm.reason })
  ElMessage.success('证据已作废，清单状态已按最新凭证复核（历史记录保留）')
  voidVisible.value = false
  await refreshDetail(); await loadCompliance(); await loadOverview()
}

// 轮询：有运行中作业或打开作业抽屉时，进度与服务端保持一致；轮询不触发重算，只读
async function pollTick() {
  const running = jobs.value.some((j) => j.running)
  if (running || jobDrawer.value) {
    const prevJobs = jobs.value
    await loadJobs()
    if (jobDrawer.value && activeJob.value.id) {
      activeJob.value = jobs.value.find((j) => j.id === activeJob.value.id) || activeJob.value
      jobItems.value = await adApi.jobItems(activeJob.value.id)
    }
    if (running && !jobs.value.some((j) => j.running)) {
      // 作业刚结束：刷新清单与总览，让余量/状态与服务端收敛结果一致
      await loadCompliance(); await loadOverview()
    }
  }
}

onMounted(async () => {
  await reloadAll()
  pollTimer = setInterval(pollTick, 2000)
})
onUnmounted(() => pollTimer && clearInterval(pollTimer))
</script>

<style scoped>
.ad-page { display: flex; flex-direction: column; gap: 10px; }
.ad-kpis { display: flex; align-items: center; gap: 14px; }
.kpi { display: flex; flex-direction: column; align-items: center; min-width: 84px; padding: 4px 10px; background: #f4f7fb; border-radius: 6px; }
.kpi b { font-size: 20px; }
.kpi span { font-size: 12px; color: #66707a; }
.kpi-danger b { color: #c62828; }
.kpi-warn b { color: #ef6c00; }
.kpi-info b { color: #1565c0; }
.kpi-ok b { color: #2e7d32; }
.ad-filters { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
.rem-over { color: #c62828; font-weight: 700; }
.rem-near { color: #ef6c00; font-weight: 700; }
.ad-sub { color: #8a949e; font-size: 12px; }
.ad-subwarn { color: #b26a00; font-size: 12px; }
.ad-pending-reason { color: #b26a00; }
.ad-tabs :deep(.el-tabs__content) { padding-top: 4px; }
.rev-head { display: flex; align-items: center; gap: 8px; }
.detail-actions { margin: 10px 0; display: flex; gap: 8px; flex-wrap: wrap; }
.evidence-row { display: flex; gap: 8px; margin-bottom: 6px; }
:deep(.row-overdue) { background: #fdecea !important; }
:deep(.row-pending) { background: #fff7e6 !important; }
h4 { margin: 14px 0 6px; font-size: 14px; }
</style>

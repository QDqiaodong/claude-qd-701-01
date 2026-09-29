<template>
  <el-form label-width="110px" size="small">
    <template v-if="form.adNo !== undefined">
      <el-divider content-position="left">指令信息</el-divider>
      <el-form-item label="指令编号" required>
        <el-input v-model="form.adNo" placeholder="如 CAD-2026-101" style="width: 240px" />
      </el-form-item>
      <el-form-item label="发布机构" required>
        <el-input v-model="form.authority" placeholder="如 CAAC 华东局" style="width: 240px" />
      </el-form-item>
    </template>

    <el-divider content-position="left">修订版内容</el-divider>
    <el-form-item label="版本标题" required>
      <el-input v-model="rev.title" placeholder="本版指令标题" />
    </el-form-item>
    <el-form-item label="生效日期" required>
      <el-date-picker v-model="rev.effectiveDate" type="date" value-format="YYYY-MM-DD" placeholder="生效日期" />
    </el-form-item>
    <el-form-item label="适用机型" required>
      <el-select v-model="rev.applicableModels" multiple placeholder="选择适用机型（可多选）" style="width: 100%">
        <el-option v-for="m in models" :key="m" :label="m" :value="m" />
      </el-select>
    </el-form-item>
    <el-form-item label="机号范围">
      <div class="range-row">
        <el-input v-model="rev.tailRangeFrom" placeholder="下界，如 B-6000（留空=不限）" style="width: 260px" />
        <span>~</span>
        <el-input v-model="rev.tailRangeTo" placeholder="上界，如 B-6999（只填一边=待核实）" style="width: 260px" />
      </div>
    </el-form-item>

    <el-divider content-position="left">截止阈值（任一维度先到即按最早限制执行；留空=该维度不限）</el-divider>
    <el-form-item label="日历截止日期">
      <el-date-picker v-model="rev.dueDate" type="date" value-format="YYYY-MM-DD" placeholder="不设日期阈值可留空" />
    </el-form-item>
    <el-form-item label="截止飞行小时">
      <el-input-number v-model="rev.dueFh" :min="0" :step="100" controls-position="right" placeholder="不限" />
    </el-form-item>
    <el-form-item label="截止起落次数">
      <el-input-number v-model="rev.dueCycles" :min="0" :step="100" controls-position="right" placeholder="不限" />
    </el-form-item>

    <el-divider content-position="left">临期预警提前量（各维度独立配置）</el-divider>
    <el-form-item label="提前(天)">
      <el-input-number v-model="rev.warnDays" :min="0" controls-position="right" />
      <span class="hint">距日历截止 ≤ 此天数即临期</span>
    </el-form-item>
    <el-form-item label="剩余飞行小时">
      <el-input-number v-model="rev.warnFh" :min="0" :step="50" controls-position="right" />
      <span class="hint">FH 余量 ≤ 此值即临期</span>
    </el-form-item>
    <el-form-item label="剩余起落次数">
      <el-input-number v-model="rev.warnCycles" :min="0" controls-position="right" />
      <span class="hint">起落余量 ≤ 此值即临期</span>
    </el-form-item>
    <el-form-item label="修订说明">
      <el-input v-model="rev.revisionNote" type="textarea" :rows="2" placeholder="本版变更摘要" />
    </el-form-item>
  </el-form>
</template>

<script setup>
import { computed } from 'vue'

const models = ['B737', 'A320', 'A330', 'ARJ21']
const props = defineProps({ form: { type: Object, required: true } })
const rev = computed(() => props.form.revision)
</script>

<style scoped>
.range-row { display: flex; align-items: center; gap: 8px; }
.hint { margin-left: 10px; color: #90a4ae; font-size: 12px; }
</style>

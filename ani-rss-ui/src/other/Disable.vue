<template>
  <!--
    伪禁用容器。
    之前只用 opacity + pointer-events:none：鼠标点不进去，但键盘仍能 Tab 进内部的
    el-input / el-button 并回车改值。这里补上 inert（原生禁用焦点与命中测试）
    与 aria-disabled（读屏可感知），并把 opacity 从 0.4 提到 0.6 以保住文字对比度。
    inert 在旧浏览器上会被忽略，此时退化为原来的 pointer-events 行为。
  -->
  <div
      class="disable-box"
      :class="{'is-disabled': !props.modelValue}"
      :inert="!props.modelValue ? true : undefined"
      :aria-disabled="props.modelValue ? undefined : 'true'">
    <slot/>
  </div>
</template>

<script setup>
let props = defineProps({
  modelValue: Boolean
})
</script>

<style scoped>
.disable-box.is-disabled {
  opacity: .6;
  pointer-events: none;
}
</style>

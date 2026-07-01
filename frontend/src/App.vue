<script setup lang="ts">
import AppShell from './components/layout/AppShell.vue'
import ToastContainer from './components/ui/ToastContainer.vue'
</script>

<template>
  <AppShell />
  <Teleport to="body">
    <ToastContainer />
  </Teleport>
</template>

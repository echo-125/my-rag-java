<script setup lang="ts">
import AppSidebar from './AppSidebar.vue'
</script>

<template>
  <div class="flex h-screen overflow-hidden">
    <AppSidebar />
    <main class="flex-1 flex flex-col min-w-0 h-screen overflow-hidden">
      <router-view />
    </main>
  </div>
</template>

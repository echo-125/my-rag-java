<script setup lang="ts">
import { Toaster } from 'vue-sonner'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
</script>

<template>
  <UApp>
    <Toaster
      position="top-right"
      :theme="'dark'"
      :duration="3000"
      :rich-colors="true"
    />
    <DefaultLayout>
      <RouterView />
    </DefaultLayout>
  </UApp>
</template>

import { defineConfig } from 'vite';
import { resolve } from 'path';

export default defineConfig({
    publicDir: false,
    build: {
        outDir: resolve(import.meta.dirname, 'android/app/src/main/assets/seb-extension'),
        emptyOutDir: false,
        sourcemap: false,
        lib: {
            entry: resolve(import.meta.dirname, 'src/android-ext/background.ts'),
            name: 'SebAndroidExtension',
            formats: ['iife'],
            fileName: () => 'background.js'
        }
    }
});

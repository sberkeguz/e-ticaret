import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

const react = {
  extends: [
    reactHooks.configs.flat.recommended,
    reactRefresh.configs.vite,
  ],
  languageOptions: {
    globals: globals.browser,
    parserOptions: { ecmaFeatures: { jsx: true } },
  },
}

export default defineConfig([
  globalIgnores(['dist']),
  // Henüz TypeScript'e çevrilmemiş dosyalar
  {
    files: ['**/*.{js,jsx}'],
    ...react,
    extends: [js.configs.recommended, ...react.extends],
  },
  // TypeScript dosyaları
  {
    files: ['**/*.{ts,tsx}'],
    ...react,
    extends: [js.configs.recommended, ...tseslint.configs.recommended, ...react.extends],
  },
])

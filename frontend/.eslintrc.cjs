/* eslint 配置（docs/04 §1.1：vue-tsc + ESLint 为 CI 阻塞项）。 */
module.exports = {
  root: true,
  env: { browser: true, es2022: true, node: true },
  parser: 'vue-eslint-parser',
  parserOptions: {
    parser: '@typescript-eslint/parser',
    ecmaVersion: 'latest',
    sourceType: 'module',
  },
  extends: ['plugin:vue/vue3-recommended'],
  plugins: ['@typescript-eslint', 'vue'],
  rules: {
    // M0 骨架：允许少量占位（未用变量在 strict tsc 已拦截，这里只管 Vue/TS 语法与致命问题）
    'vue/multi-word-component-names': 'off',
    'vue/max-attributes-per-line': 'off',
    'vue/singleline-html-element-content-newline': 'off',
    'vue/html-self-closing': 'off',
    'vue/html-indent': 'off',
    'vue/attributes-order': 'off',
    'no-useless-escape': 'error',
  },
  ignorePatterns: ['dist/', 'node_modules/', '*.cjs'],
}

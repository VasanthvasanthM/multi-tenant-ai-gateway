import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'coverage'] },
  ...tseslint.configs.recommended,
  {
    rules: {
      // The API types are hand-written to match docs/api/openapi.yaml. An `any`
      // anywhere in that path defeats the only mechanism keeping them honest.
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/consistent-type-imports': 'error',

      // A leading underscore marks a deliberate discard. Needed for the
      // destructure-to-omit idiom - `const { x: _x, ...rest } = obj` - which is
      // how a test builds a payload with one field missing without hand-writing
      // the other twelve.
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
      ],
    },
  },
);

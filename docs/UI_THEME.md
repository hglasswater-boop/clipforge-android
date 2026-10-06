# UI theme

ClipForge の UI トーンは XFiles を基準にする。

参照実装は XFiles の `app/src/main/java/app/local1st/files/ui/theme/Theme.kt` とし、ClipForge 独自のブランド色や薄紫系の固定パレットは持たない。

## Theme policy

- Android 12 (API 31) 以降で Dynamic Color が有効な場合:
  - light: `dynamicLightColorScheme()`
  - dark: `dynamicDarkColorScheme()`
- Dynamic Color を使用しない場合:
  - light: `expressiveLightColorScheme()`
  - dark: `darkColorScheme()`
- theme wrapper は `MaterialExpressiveTheme` を使用する。
- motion は `MotionScheme.expressive()` を使用する。
- edge-to-edge の status bar / navigation bar のアイコン明暗は、実際の light / dark theme に追従させる。
- API 26-28 の navigation bar scrim は XFiles と同じ light / dark 値を使う。

## Implementation rule

Theme policy は `ClipForgeTheme` に集約する。Activity や各画面で `MaterialTheme`、`lightColorScheme()`、`darkColorScheme()` を直接選択しない。

テーマ選択条件は純粋関数としてテスト可能にし、次のケースを固定する。

1. API 31+ / Dynamic Color 有効 / light -> Dynamic Light
2. API 31+ / Dynamic Color 有効 / dark -> Dynamic Dark
3. API 30 以下 / light -> Expressive Light
4. Dynamic Color 無効 / light -> Expressive Light
5. Dynamic Color 無効または API 30 以下 / dark -> Dark

## Scope

今回変更するのはアプリ全体の theme policy と system bar のトーンであり、画面構造、編集操作、レイアウト、データ処理は変更しない。

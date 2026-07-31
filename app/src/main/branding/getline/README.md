# Temporary GetLine brand references

These files were downloaded from the public GetLinePro production app on
2026-07-26. They are kept outside Android resource directories as source
references for the future launcher-icon pass.

- `pwa_icon_192.png`: `https://app.getline.pro/api/pwa/icon/192`
- `pwa_icon_512.png`: `https://app.getline.pro/api/pwa/icon/512`
- `getline_pro_wordmark.svg`: lockup — brand G mark + Mulish 700 “etLine Pro”
  (G path from `https://getline.pro/logo.svg` / `DS_GetLine_LOGO_UI_FILL.svg`)
- `getline_pro_wordmark_preview.png`: dark-bg render of the wordmark for QA

Runtime-ready shared assets live in the `design` / `getlineui` modules:

- `res/raw/getline_logo_source.svg`: original vector from
  `https://getline.pro/logo.svg`
- `res/raw/getline_wordmark_source.svg`: copy of `getline_pro_wordmark.svg`,
  the source of `res/drawable/ic_getline_wordmark.xml`
- `res/drawable-nodpi/getline_logo.png`: transparent fallback from
  `https://app.getline.pro/api/branding/banner`
- `res/font/mulish_variable.ttf`: Mulish variable font from Google Fonts
- `res/raw/mulish_ofl.txt`: font license

The production branding endpoint currently identifies the animated background
as a flow field with opacity `0.5`, size `1`, and speed `1`. It should be
implemented natively rather than captured as a bitmap.

Replace these temporary exports with canonical design sources when they become
available.

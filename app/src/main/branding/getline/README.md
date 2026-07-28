# Temporary GetLine brand references

These files were downloaded from the public GetLinePro production app on
2026-07-26. They are kept outside Android resource directories as source
references for the future launcher-icon pass.

- `pwa_icon_192.png`: `https://app.getline.pro/api/pwa/icon/192`
- `pwa_icon_512.png`: `https://app.getline.pro/api/pwa/icon/512`

Runtime-ready shared assets live in the `design` module:

- `res/raw/getline_logo_source.svg`: original vector from
  `https://getline.pro/logo.svg`
- `res/drawable-nodpi/getline_logo.png`: transparent fallback from
  `https://app.getline.pro/api/branding/banner`
- `res/font/mulish_variable.ttf`: Mulish variable font from Google Fonts
- `res/raw/mulish_ofl.txt`: font license

The production branding endpoint currently identifies the animated background
as a flow field with opacity `0.5`, size `1`, and speed `1`. It should be
implemented natively rather than captured as a bitmap.

Replace these temporary exports with canonical design sources when they become
available.

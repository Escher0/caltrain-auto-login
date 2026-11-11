# Licensing

Copyright (C) 2026 Brian Pilnick

Unless otherwise noted, original project material in this directory is licensed
under the GNU General Public License, version 3 or (at your option) any later
version (`GPL-3.0-or-later`), with the additional permission below. This applies
to the Android and desktop applications, shared code, prototypes, documentation
and original artwork. Third-party components retain their own licenses.

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program. See [LICENSE](LICENSE) or <https://www.gnu.org/licenses/>.

## Additional permission for Microsoft WebView2

Additional permission under GNU GPL version 3 section 7: If you modify this
Program, or any covered work, by linking or combining it with the Microsoft Edge
WebView2 SDK loader or the Microsoft Edge WebView2 Runtime (or modified versions
of those components), containing parts covered by the applicable Microsoft
license terms, the licensors of this Program grant you additional permission to
convey the resulting work.

This permission is limited to those WebView2 components. The covered project
material remains subject to the GPL, and this permission does not replace the
license terms of third-party components.

## File-level notices

Original source files carry short copyright and SPDX headers. `REUSE.toml`
records notices for artwork, documentation, JSON, lockfiles and generated
permission files. It separately identifies the Gradle wrapper as Apache-2.0;
upstream notices must be retained.

The SPDX identifier records the base GPL-3.0-or-later license. The additional
WebView2 permission above is granted alongside it and is referenced by source
headers and `REUSE.toml`. It is not a standard SPDX exception identifier.

When adding original source files, use the same header and retain any additional
contributor notices. For files that cannot carry comments or are generated, add
an annotation in `REUSE.toml`. Do not apply the project's copyright or GPL header
to third-party files; record their actual notices instead. Keep license texts
unchanged in `LICENSES/`.

Check coverage from the repository root with `reuse lint` (or `uvx reuse lint`).
This checks notice coverage and formatting, not dependency license compatibility.

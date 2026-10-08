#!/usr/bin/env bash
# Builds the user's playbook, in Spanish and English, from playbook/:
#
#   scripts/playbook.sh            the version in the poms
#   scripts/playbook.sh 1.0.3      that version, as the release does with its tag
#
# Writes playbook/build/sheriff-playbook-es.pdf and sheriff-playbook-en.pdf,
# which .github/workflows/release.yml attaches to every release. The version
# is printed on the cover and in every page's footer, so the PDF a user has
# says which tools it describes. Both are built from scratch every time (-g):
# latexmk does not count a new version as a change, and kept the old one.
#
# Needs a TeX distribution with latexmk and pdflatex, and the packages the
# style loads (sheriff-playbook.sty): on Debian or Ubuntu, latexmk,
# texlive-latex-extra, texlive-fonts-extra and texlive-lang-spanish.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="$repo/playbook"
pom="$repo/sheriff-mcp-java/pom.xml"

if ! command -v latexmk >/dev/null 2>&1; then
  echo "playbook.sh: latexmk is not installed. On Debian or Ubuntu:" >&2
  echo "  sudo apt-get install latexmk texlive-latex-extra texlive-fonts-extra texlive-lang-spanish" >&2
  exit 1
fi

# The project's own version: the first <version> after its artifactId, as the
# dependencies' versions come later.
version="${1:-$(sed -n '/<artifactId>sheriff-mcp<\/artifactId>/,/<version>/s|.*<version>\(.*\)</version>.*|\1|p' "$pom" | head -n 1)}"
if [[ -z "$version" ]]; then
  echo "playbook.sh: no version given, and none found in $pom" >&2
  exit 1
fi

# The Maven plugin's example names the plugin's own version, which a
# prerelease tag (1.0.3-rc.1) is not.
plugin_version="$(sed -n '/<artifactId>sheriff-maven-plugin<\/artifactId>/,/<version>/s|.*<version>\(.*\)</version>.*|\1|p' "$repo/sheriff-maven-plugin/pom.xml" | head -n 1)"

cd "$source_dir"
for language in es en; do
  latexmk -g -pdf -bibtex- -interaction=nonstopmode -halt-on-error -outdir=build \
    -usepretex="\\def\\playbookversion{$version}\\def\\pluginversion{$plugin_version}" \
    "sheriff-playbook-$language.tex" >/dev/null 2>&1 || {
      echo "playbook.sh: sheriff-playbook-$language.tex did not build; the log says why:" >&2
      grep -a -A 8 '^!' "build/sheriff-playbook-$language.log" >&2 || true
      exit 1
    }
done
echo "Playbook $version: $source_dir/build/sheriff-playbook-es.pdf, sheriff-playbook-en.pdf"

from html.parser import HTMLParser
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
SITE = ROOT / "control-center"
INDEX = SITE / "index.html"
SCRIPT = SITE / "app.js"
STYLES = SITE / "styles.css"


class DashboardMarkupParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()
        self.views = set()
        self.sections = set()
        self.assets = set()
        self.external_blank_links = []

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        element_id = values.get("id")
        if element_id:
            self.ids.add(element_id)
            if tag == "section" and element_id.startswith("view-"):
                self.sections.add(element_id.removeprefix("view-"))
        if "data-view" in values:
            self.views.add(values["data-view"])
        if tag == "link" and values.get("rel") == "stylesheet":
            self.assets.add(values.get("href"))
        if tag == "script" and values.get("src"):
            self.assets.add(values["src"])
        href = values.get("href", "")
        if href.startswith("https://") and values.get("target") == "_blank":
            self.external_blank_links.append(values)


class ControlCenterAssetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.html = INDEX.read_text(encoding="utf-8")
        cls.javascript = SCRIPT.read_text(encoding="utf-8")
        cls.css = STYLES.read_text(encoding="utf-8")
        cls.parser = DashboardMarkupParser()
        cls.parser.feed(cls.html)

    def test_html_references_existing_local_assets(self):
        self.assertIn("./app.js", self.parser.assets)
        self.assertIn("./styles.css", self.parser.assets)
        self.assertTrue(SCRIPT.is_file())
        self.assertTrue(STYLES.is_file())

    def test_navigation_targets_existing_views(self):
        self.assertEqual(self.parser.views, self.parser.sections)

    def test_literal_javascript_dom_targets_exist_in_html(self):
        targets = set(re.findall(r'\$\("([^"]+)"\)', self.javascript))
        self.assertTrue(targets)
        self.assertFalse(targets - self.parser.ids, targets - self.parser.ids)

    def test_external_links_open_safely(self):
        for link in self.parser.external_blank_links:
            rel = set(link.get("rel", "").split())
            self.assertIn("noreferrer", rel)
        self.assertIn('target="_blank" rel="noreferrer"', self.javascript)

    def test_github_data_is_escaped_before_html_interpolation(self):
        self.assertIn("const esc =", self.javascript)
        for field in (
            "run.html_url", "run.name", "run.head_branch", "run.head_sha",
            "pr.html_url", "pr.title", "commit.html_url",
        ):
            self.assertIn(f"esc({field})", self.javascript)

    def test_dashboard_is_public_read_only_and_has_no_embedded_credentials(self):
        self.assertIn(
            'https://api.github.com/repos/shivugitec-source/project-axiom',
            self.javascript,
        )
        self.assertNotRegex(self.javascript, r'\bAuthorization\s*:')
        self.assertNotRegex(self.javascript, r'gh[pousr]_[A-Za-z0-9]{20,}')
        self.assertNotRegex(self.javascript, r'\b(?:eval|Function)\s*\(')
        self.assertNotIn("document.write", self.javascript)

    def test_stylesheet_has_mobile_layout_rules(self):
        self.assertIn("@media(max-width:720px)", self.css)


if __name__ == "__main__":
    unittest.main()

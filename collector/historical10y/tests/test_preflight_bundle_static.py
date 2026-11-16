from __future__ import annotations
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class PreflightBundleStaticTest(unittest.TestCase):
    def test_bundle_does_not_contain_data_open_calls(self):
        p = (ROOT / "run_jvlink_preflight_bundle.ps1").read_text(encoding="utf-8")
        self.assertNotIn(".JVOpen(", p)
        self.assertNotIn(".JVRTOpen(", p)
        self.assertIn("credential_material_recorded = $false", p)
        self.assertIn("Get-FileHash -Algorithm SHA256", p)

    def test_bundle_requires_explicit_contract_booleans(self):
        p = (ROOT / "run_jvlink_preflight_bundle.ps1").read_text(encoding="utf-8")
        self.assertIn("[Parameter(Mandatory=$true)]", p)
        self.assertIn("[string]$ContractActive", p)
        self.assertIn("[string]$UserKeyValid", p)


if __name__ == "__main__":
    unittest.main()

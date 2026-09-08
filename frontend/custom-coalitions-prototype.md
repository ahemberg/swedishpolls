# Custom coalition prototype

Throwaway review artifact for [Explore custom coalitions with support history](https://github.com/ahemberg/swedishpolls/issues/15). All numerical values are fictional. This is not a fitted model or production API.

Run from this worktree:

```sh
python3 -m http.server 4173 --directory frontend
```

Open http://localhost:4173/custom-coalitions-prototype.html?variant=A . The floating arrows switch between A, stacked controls; B, a side editor; and C, a compact editor. All retain the preset selector followed by party checkboxes. Mobile layouts stack. No application route imports these files.

Review empty and single-party selections, a preset edit, Swedish text, a narrow screen, keyboard controls, and reloading the generated URL. The example starts from M + KD + L + SD. The fictional missing-data period and separate-fit boundary demonstrate line breaks. Date inputs choose the range; the slider chooses the readout date. Only the range is shared.

```sh
node frontend/custom-coalitions-prototype.check.cjs
```

The check exercises the script with a minimal DOM stub. It does not verify browser rendering or accessibility. Uncertainty comes from sums within fictional joint composition draws; these draws are only an illustration. The production contract still needs to specify validated joint coalition summaries.

Owner-approved scope: post-v1, one editable grouping, eight current parties only, combined voting-intention history with uncertainty, empty and single-party states, coalition-page placement, no 50% reference line, shareable parties and date range using the latest publication with its date visible. No seats, majority probabilities, accounts, saved lists, custom images or downloads.

Layout verdict and production data contract remain pending. Do not merge this prototype into main.

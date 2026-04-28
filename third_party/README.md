# Third-Party Dependencies

This directory records dependency and license metadata for the desktop app.

Python packages are no longer vendored here. The app creates a runtime virtual
environment from the system Python and installs packages from PyPI using
`python_detector/requirements.txt`.

Keep `THIRD_PARTY_MANIFEST.json` updated when application or Python dependencies
change.

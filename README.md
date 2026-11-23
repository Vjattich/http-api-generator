# BuildApi

A script that generates a fully complete class with java native http calls and response parsing from .http format files.

Script uses special annotation:
- @response is json/record describing of returning object
- @param **;instance** on any param mark as instance this paramter will be passed in constructor of object not in method argument

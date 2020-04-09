package org.sorz.lab.blescanner

import java.lang.Exception

class ScanFailException(errorCode: Int) : Exception("error code $errorCode")

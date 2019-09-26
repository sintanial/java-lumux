package com.lumux.exception;

import java.io.IOException;

public class TooManyStreamsException extends IOException {
    public TooManyStreamsException(String s) {
        super(s);
    }
}

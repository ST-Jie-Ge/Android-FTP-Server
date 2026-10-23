package org.apache.ftpserver.command.impl;
import org.junit.Test;
import org.apache.ftpserver.ftplet.DataType;
import static org.junit.Assert.*;
public class TypeParameterTest {
    @Test public void supportedTypes() {
        assertEquals(DataType.ASCII, TYPE.parseType("A"));
        assertEquals(DataType.ASCII, TYPE.parseType("A N"));
        assertEquals(DataType.BINARY, TYPE.parseType("I"));
        assertEquals(DataType.BINARY, TYPE.parseType("L 8"));
    }
    @Test public void unsupportedParameters() {
        for (String type : new String[]{"ABC","A T","A C","I N","L 16","E",""}) {
            try { TYPE.parseType(type); fail(type); } catch(IllegalArgumentException expected) { }
        }
    }
}

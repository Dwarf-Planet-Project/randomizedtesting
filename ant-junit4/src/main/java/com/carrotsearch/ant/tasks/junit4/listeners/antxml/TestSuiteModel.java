package com.carrotsearch.ant.tasks.junit4.listeners.antxml;

import java.util.List;

import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Root;

import com.google.common.collect.Lists;

/**
 * Suite model of ANT-JUnit XML.
 */
@Root(name = "testsuite")
public class TestSuiteModel
{
    @Attribute
    public int errors;

    @Attribute
    public int failures;

    @Attribute
    public int tests;

    @Attribute
    public String name;
    
    @Attribute
    public String hostname;

    @Attribute
    public double time;

    @Attribute
    public String timestamp;

    @ElementList
    public List<PropertyModel> properties = Lists.newArrayList();

    @ElementList(inline = true)
    public List<TestCaseModel> testcases = Lists.newArrayList();

    @Element(name = "system-out", data = true, required = true)
    public String sysout = "";

    @Element(name = "system-err", data = true, required = true)
    public String syserr = "";
}
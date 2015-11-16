package com.eviware.soapui.impl.wsdl

import com.eviware.soapui.support.UISupport
import groovy.io.FileType
import groovy.xml.XmlUtil
import static groovy.xml.XmlUtil.serialize

class SplittedProject {
    private File startDir
    private Node projectRoot
    private String environment

    def prepareSave(File path, String xmlString) {
        startDir = path			// working directory
        projectRoot = new XmlParser().parseText(xmlString)		// projectRoot contains parsed XML
    }

    def saveProject(String environment) {
        this.environment = environment

        try {
            if (!startDir.exists())
                startDir.mkdir()				// delete directory and create new
            //writeHeader(projectRoot)
            recursiveWrite(projectRoot, startDir)	// split into files
        } catch (Exception e) {
            // catch exceptions and show them in script log and in UI
            UISupport.showExtendedInfo("Project Splitter", "Error occured while saving:", e.toString(), new java.awt.Dimension(500, 300))
        }
    }

    def writeHeader(Node node, File currentDir) {
        def file = new File(currentDir.getCanonicalPath() + File.separator + "__header.xml")
        Node header = node.clone()
        header.children().each {
            it.setValue([])
        }
        file.write(XmlUtil.serialize(header).toString())
        return header
    }

    Node readHeader(File currentDir) {
        def file = new File(currentDir.getCanonicalPath() + File.separator + "__header.xml")
        if (file.exists())
            return new XmlParser().parseText(file.text)
        else
            return null
    }

    // main function that writes files from xml from memory and writes them to the file system
    def recursiveWrite(Node node, File currentDir) {
        // check if __header.xml exists
        def nodeHeader = readHeader(currentDir)
        Node tmpNode = node.clone()
        tmpNode.children().each { it.setValue([]) }
        if (nodeHeader == null) {
            nodeHeader = writeHeader(node, currentDir)
        } else {
            String j1 = nodeHeader.toString()
            String j2 = tmpNode.toString()
            if (j1 != j2)
                nodeHeader = writeHeader(node, currentDir)
        }
        // check for changes
        ArrayList<String> listFiles = new ArrayList<String>()
        ArrayList<String> listDirs = new ArrayList<String>()
        listFiles.add("__header.xml")

        node.children().each {
            if (it.children().size() > 0) {     // skip empty nodes
                if (it.@name == null) { // if this is a property, settings, teststep, method, then write it into file
                    def file
                    if (it.name().localPart == "properties" && currentDir == startDir) {
                        file = new File(currentDir.getCanonicalPath() + File.separator + environment + "_" + strToSafeName(it.name().localPart) + ".xml")
                    } else {
                        file = new File(currentDir.getCanonicalPath() + File.separator + strToSafeName(it.name().localPart) + ".xml")
                    }
                    if (file.exists()) {
                        def fileNode = new XmlParser().parseText(file.text)
                        String s1 = fileNode.toString()
                        String s2 = it.toString()
                        if (s1 != s2) {
                            file.write(XmlUtil.serialize(it).toString())
                        }
                    } else {
                        file.createNewFile()
                        file.write(XmlUtil.serialize(it).toString())
                    }
                    listFiles.add(file.getName())
                } else {        // this is a testsuite, testcase, so create a directory
                    File dir = new File(currentDir.getCanonicalPath() + File.separator + strToSafeName(it.@name))
                    if (!dir.exists())
                        dir.mkdir()
                    // recurse into directory
                    recursiveWrite(it, dir)
                    listDirs.add(dir.getName())
                }
            }
        }
        // delete unneeded files
        deleteFilesNotInList(currentDir, listFiles, listDirs)
    }

    def deleteFilesNotInList(File currentDir, ArrayList<String> listFiles, ArrayList<String> listDirs) {
        currentDir.eachFile(FileType.FILES) {
            def fileName = it.getName()
            if (!listFiles.contains(fileName) && !(currentDir == startDir && fileName.matches(~/.*_properties.xml/)))
                it.delete()
        }

        currentDir.eachFile(FileType.DIRECTORIES) {
            def dirName = it.getName()
            if (!listDirs.contains(dirName)) {
                it.deleteDir()
            }
        }
    }

    def prepareLoad(File path) {
        startDir = path
        projectRoot = null
    }

    String loadProject(String environment)
    {
        this.environment = environment

        try {
            projectRoot = recursiveRead(startDir)	// contruct xml in memory
            return serialize(projectRoot).toString()
        } catch (Exception e) {
            // catch exceptions and show them in script log and in UI
            UISupport.showExtendedInfo("Project Splitter", "Error occurred while loading:", e.toString(), new java.awt.Dimension(500, 300))
        }
    }

    String[] getEnvironments()
    {
        ArrayList<String> environments = new ArrayList<>()

        startDir.eachFileMatch(~/.*_properties.xml/) {
            environments.add(it.getName().split("_properties.xml")[0])
        }

        return environments.toArray()
    }
    // main function that reads files from filesystem and constructs Parsed XML in memory
    def recursiveRead(File currentDir) {
        Node currentNode = null

        def nodeHeader = readHeader(currentDir)
        if (nodeHeader == null)
            return
        else
            currentNode = nodeHeader.clone()

        currentNode.setValue([])

        nodeHeader.children().each {
            if (it.@name == null) { // if this is a property, settings, teststep, method, then write it into file
                def file
                if (it.name().localPart == "properties" && currentDir == startDir) {
                    file = new File(currentDir.getCanonicalPath() + File.separator + environment + "_" + strToSafeName(it.name().localPart) + ".xml")
                } else {
                    file = new File(currentDir.getCanonicalPath() + File.separator + strToSafeName(it.name().localPart) + ".xml")
                }
                if (file.exists()) {
                    def fileNode = new XmlParser().parseText(file.text)
                    currentNode.append(fileNode)
                } else {
                    return null
                }
            } else {        // this is a testsuite, testcase, so create a directory
                File dir = new File(currentDir.getCanonicalPath() + File.separator + strToSafeName(it.@name))
                if (dir.exists()) {
                    currentNode.append(recursiveRead(dir))
                }
                else
                    return null
            }
        }
        return currentNode
    }

    public static String strToSafeName(String name) {
        int size = name.length()
        StringBuffer rc = new StringBuffer(size * 5)
        for (int i = 0; i < size; i++) {
            char c = name.charAt(i)
            boolean valid = c >= 'a' && c <= 'z'
            valid = valid || (c >= 'A' && c <= 'Z')
            valid = valid || (c >= '0' && c <= '9')
            valid = valid || (c == '_') || (c == '-') || (c == '.') || (c == ' ')

            if (valid) {
                rc.append(c)
            } else {
                // Encode the character using hex notation
                rc.append('#')
                int symbol = c as int
                rc.append(String.format("%04X", symbol))
            }
        }
        String result = rc.toString()
        if (result == "")
            result = "#FFFF"
        return result;
    }

    public String safeNameToStr(String safeName) {
        int size = safeName.length()
        StringBuffer rc = new StringBuffer(size)
        int i = 0;
        while (i < size) {
            char c = safeName.charAt(i)
            if (c != '#') {
                rc.append(c)
                i++
            } else {
                String hex = safeName[i+1] + safeName[i+2] + safeName[i+3] + safeName[i+4]
                int parseInt = Integer.parseInt(hex, 16)
                char c1 = (char)parseInt
                rc.append(c1)
                i += 5
            }
        }
        String result = rc.toString()
        return result
    }
}
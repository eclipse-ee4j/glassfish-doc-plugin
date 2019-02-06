/*
 * Copyright (c) 2011, 2019 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.doc;

import java.io.*;
import java.util.*;
import java.util.regex.*;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.model.Resource;
import org.codehaus.plexus.resource.ResourceManager;
import org.codehaus.plexus.resource.loader.FileResourceCreationException;
import org.codehaus.plexus.resource.loader.FileResourceLoader;
import org.codehaus.plexus.resource.loader.ResourceNotFoundException;
import org.codehaus.plexus.util.FileUtils;

/**
 * Generate Table of Contents (TOC) for asciidoc jbake projects.
 */
@Mojo(name = "toc", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class TocMojo extends AbstractMojo {
    /**
     * Name of the title page file.
     * Scanning for TOC entries starts with this file.
     */
    @Parameter(property = "toc.titlepage", defaultValue = "title.adoc")
    protected String titlePage;

    /**
     * The title to use in the TOC.
     */
    @Parameter(property = "toc.title")
    protected String title;

    /**
     * Name of the table of contents file.
     */
    @Parameter(property = "toc.toc", defaultValue = "toc.adoc")
    protected String toc;

    /**
     * Regular expressions that indicate a new chapter.
     * Matched against top level section titles.
     * Multiple expressions are separated by commas.
     */
    @Parameter(property = "toc.chapterpatterns", defaultValue = "[0-9]+\\s.*")
    protected String chapterPatterns;

    /**
     * Regular expressions of tags that should be ignored.
     * Multiple expressions are separated by commas.
     */
    @Parameter(property = "toc.tagpatterns", defaultValue = "")
    protected String ignoreTagPatterns;

    /**
     * Base directory for project.
     * Should not need to be set.
     */
    @Parameter(defaultValue = "${project.basedir}")
    protected File baseDirectory;

    /**
     * Jbake directory containing the asciidoc files.
     */
    @Parameter(property = "toc.dir",
		defaultValue = "${project.basedir}/src/main/jbake/content")
    protected File sourceDirectory;

    /**
     * Turn on debugging.
     */
    @Parameter(property = "toc.debug")
    protected boolean debug;

    /**
     * Log output, initialize this in the execute method.
     */
    protected Log log;

    /**
     * @component
     * @required
     * @readonly
     */
    @Component
    private ResourceManager resourceManager;

    private String next;	// "next" link
    private String prev;	// "prev" link
    private PrintWriter tout;	// the TOC file

    private Set<String> seen = new HashSet<>();	// files we've seen
    private String[] chapterList;	// chapter title regular expressions
    private String[] tagList;	        // ignored tag regular expressions
    private Pattern tagPattern;
    // following for error messages
    private String curfile;
    private String lastline;
    private int lineno;

    // amount of slop to allow in "underlines" for section header lines
    private static final int HEADER_SLOP = 5;

    @Override
    public void execute() throws MojoExecutionException {
        log = getLog();
        chapterList = chapterPatterns.split(",");
        tagPattern = Pattern.compile("\\[\\[([-a-zA-Z0-9]+)]]");
	if (ignoreTagPatterns != null)	// XXX - default value "" becomes null?
	    tagList = ignoreTagPatterns.split(",");
	else
	    tagList = new String[0];

        if (log.isDebugEnabled()) {
            log.debug("baseDirectory " + baseDirectory);
            log.debug("sourceDirectory " + sourceDirectory);
            log.debug("titlePage " + titlePage);
            log.debug("title " + title);
            log.debug("toc " + toc);
            for (String p : chapterList)
                log.debug("chapterPattern " + p);
            for (String p : tagList)
                log.debug("ignoreTagPattern " + p);
        }

        try {
            // create, open, and write toc.adoc
            tout = new PrintWriter(new File(sourceDirectory, toc));
            tout.println("type=page");
            tout.println("status=published");
            tout.println("title=" + title);
            tout.println("next=" + titlePage.replace(".adoc", ".html"));
            tout.println("~~~~~~");
            tout.println(title);
            tout.println(headerLine('=', title.length()));
            tout.println();
            tout.println("[[contents]]");
            tout.println("Contents");
            tout.println("--------");
            tout.println();

            /*
             * Follow the "next" links from file to file, starting
             * with the titlePage file.
             */
            next = titlePage;
            do {
                String file = next;
                String prevfile = null;
                seen.add(next);
                walk(next);
                if (prev != null && prevfile != null &&
                        !prev.equals(prevfile)) {
                    log.error(String.format(
                            "ERROR: prev wrong in %s - is %s, should be %s\n",
                            file, prev, prevfile));
                }
                prevfile = file;
            } while (next != null);

            /*
             * Warn about files in the source directory that were not included
             * in the "next" links.
             */
            for (String name : sourceDirectory.list()) {
                if (!name.endsWith(".adoc") || name.equals("cpyr.adoc") ||
                        name.equals("toc.adoc") || name.equals(toc))
                    continue;
                if (!seen.contains(name))
                    log.warn("MISSED: " + name);
            }

            tout.close();
        } catch (IOException ex) {
            log.error(ex);
        }
    }

    /**
     * Process the named file in the source directory.
     */
    private void walk(String file) throws IOException {
        title = next = prev = null;
	curfile = file;
	lineno = 0;
        File in = new File(sourceDirectory, file);
        try (BufferedReader r = new BufferedReader(new FileReader(in))) {
            String line;
            // read and extract information from the header
            while ((line = r.readLine()) != null) {
		lineno++;
                if (line.startsWith("~"))
                    break;
                if (line.startsWith("title="))
                    title = line.substring(line.indexOf("=") + 1);
                if (line.startsWith("next="))
                    next = line.substring(line.indexOf("=") + 1).
                                replace(".html", ".adoc");
                if (line.startsWith("prev="))
                    prev = line.substring(line.indexOf("=") + 1).
                                replace(".html", ".adoc");
            }

            lastline = "";
            String biglink = "";
            String smalllink = "";
            String link = "";
            boolean seenNonEmpty = false;
            while ((line = r.readLine()) != null) {
		lineno++;
                if (line.startsWith("[[") && line.endsWith("]]")) {
                    Matcher m = tagPattern.matcher(line);
                    while (m.find()) {
                        String tag = m.group(1);
                        if (ignoreTag(tag))
                            continue;
                        if (tag.matches("[A-Z0-9]+")) {
                            biglink = tag;
                            seenNonEmpty = false;       // start looking
                        } else if (tag.matches("[a-zA-Z0-9]+")) {
                            smalllink = tag;
                            if (seenNonEmpty)
                                biglink = "";
                        } else {
                            link = tag;
                            if (biglink.isEmpty() && smalllink.isEmpty())
                                biglink = tag;
                        }
                    }
                } else if (lastline.length() < 3) {
                    // do nothing
                } else if (line.length() >= 5 &&
                        isHeader(line, "-", lastline.length())) {
                    // lastline is a title or subtitle
                    if (biglink.isEmpty())
                        biglink = smalllink;
                    if (isChapter(lastline)) {
                        // it's a chapter title
                        tout.println();
                        if (!link.isEmpty())
                            tout.printf("[[%s]]%n", link);
                        String linkline = String.format("link:%s#%s[%s]",
                                                file.replace(".adoc", ".html"),
                                                biglink, lastline);
                        tout.println(linkline);
                        tout.println(headerLine('~', linkline.length()));
                        tout.println();
                    } else {
                        // it's a subtitle
                        tout.printf("* link:%s#%s[%s]\n",
                                                file.replace(".adoc", ".html"),
                                                biglink, lastline);
                    }
                    link = "";
                    biglink = "";
                    smalllink = "";
                    seenNonEmpty = false;
                } else if (isHeader(line, "~", lastline.length())) {
                    // lastline is a subsubtitle
                    if (biglink.isEmpty())
                        biglink = smalllink;
                    tout.printf("** link:%s#%s[%s]\n",
                                                file.replace(".adoc", ".html"),
                                                biglink, lastline);
                    link = "";
                    biglink = "";
                    smalllink = "";
                    seenNonEmpty = false;
                } else if (isHeader(line, "\\^", lastline.length())) {
                    // lastline is a subsubsubtitle
                    if (biglink.isEmpty())
                        biglink = smalllink;
                    tout.printf("*** link:%s#%s[%s]\n",
                                                file.replace(".adoc", ".html"),
                                                biglink, lastline);
                    link = "";
                    biglink = "";
                    smalllink = "";
                    seenNonEmpty = false;
                } else if (!line.isEmpty()) {
                    seenNonEmpty = true;
                }
                lastline = line;
            }
        } catch (FileNotFoundException fex) {
            log.warn(in.toString() + ": can not open");
        }
    }

    /**
     * Should this tag be ignored?
     */
    private boolean ignoreTag(String tag) {
        for (String t : tagList) {
            if (tag.matches(t))
                return true;
        }
        return false;
    }

    /**
     * Is line a header line of length len using hchar?
     * Also, warn about header lines that may not have the correct
     * amount of "underlining".
     */
    private boolean isHeader(String line, String hchar, int len) {
	if (line.length() == 0)
	    return false;
	if (line.length() == len)
	    return line.matches(hchar + "{" + len + "}");
	if (len > HEADER_SLOP &&
		line.length() >= len - HEADER_SLOP &&
		line.length() <= len + HEADER_SLOP &&
		line.matches(hchar + "{" + (len-HEADER_SLOP) + "," +
					(len+HEADER_SLOP+1) + "}")) {
	    log.warn(curfile + ":" + lineno + ": header line length mismatch:");
	    log.warn(lastline);
	    log.warn(line);
	    return true;
	}
	return false;
    }

    /**
     * Return a header line of length len using hchar.
     */
    private static String headerLine(char hchar, int len) {
        char[] c = new char[len];
        Arrays.fill(c, hchar);
        return new String(c);
    }

    /**
     * Is the string a chapter title?
     */
    private boolean isChapter(String s) {
        for (String p : chapterList) {
            if (s.matches(p))
                return true;
        }
        return false;
    }
}

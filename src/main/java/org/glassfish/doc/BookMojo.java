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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Generate an asciidoc book for asciidoc jbake projects.
 * Processes all the jbake asciidoc source files that include
 * a jekyll header to remove the header and to produce a book
 * asciidoc file that includes all the processed asciidoc files.
 */
@Mojo(name = "book", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class BookMojo extends AbstractMojo {
    /**
     * Name of the start page file.
     * The book starts with this file.
     */
    @Parameter(property = "book.startpage", defaultValue = "title.adoc")
    protected String startPage;

    /**
     * The title to use in the book.
     * Defaults to title set in start page.
     */
    @Parameter(property = "book.title")
    protected String title;

    /**
     * Name of the book file.
     */
    @Parameter(property = "book.book", defaultValue = "book.adoc")
    protected String book;

    /**
     * Files to be excluded from the book.
     * If not set, toc.adoc is excluded.
     */
    @Parameter
    protected List<String> exclude;

    /**
     * Jbake directory containing the jekyll asciidoc source files.
     */
    @Parameter(property = "book.dir",
		defaultValue = "${project.basedir}/src/main/jbake/content")
    protected File sourceDirectory;

    /**
     * Name of optional attributes configuration file for the book.
     * (Default is "book-attributes.conf".)
     */
    @Parameter(property = "book.attributes", defaultValue =
            "${project.basedir}/src/main/jbake/content/book-attributes.conf")
    protected File attributesFile;

    /**
     * Output directory containing the processed asciidoc files for the book.
     */
    @Parameter(property = "book.outputdir",
		defaultValue = "${project.build.directory}/book")
    protected File bookDirectory;

    /**
     * Log output, initialize this in the execute method.
     */
    protected Log log;

    private String next;	// "next" link
    private String prev;	// "prev" link

    private Set<String> seen = new HashSet<>();	// files we've seen

    @Override
    public void execute() throws MojoExecutionException {
        log = getLog();

        if (exclude == null) {
            exclude = new ArrayList<String>();
            exclude.add("toc.adoc");
        }

        if (log.isDebugEnabled()) {
            log.debug("bookDirectory " + bookDirectory);
            log.debug("startPage " + startPage);
            log.debug("title " + title);
            log.debug("book " + book);
            log.debug("exclude " + exclude);
        }

        try {
            // create, open, and write book.adoc
	    if (!bookDirectory.exists() && !bookDirectory.mkdirs()) {
		log.error(String.format(
		    "ERROR: can't create output directory %s", bookDirectory));
		throw new MojoExecutionException("Can't create output directory");
	    }
	    if (!bookDirectory.isDirectory()) {
		log.error(String.format(
		    "ERROR: %s is not a directory", bookDirectory));
		throw new MojoExecutionException(
		    "Book directory is not a directory");
	    }

            // if title not set, get it from the start page
            if (title == null) {
                File in = new File(sourceDirectory, startPage);
                try (BufferedReader r = new BufferedReader(new FileReader(in))) {
                    String line;
                    // read and extract information from the header
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("~"))
                            break;
                        if (line.startsWith("title=")) {
                            title = line.substring(line.indexOf("=") + 1);
                            break;
                        }
                    }
                }
            }

            PrintWriter tout = new PrintWriter(new File(bookDirectory, book));
            tout.printf("= %s%n", title);

            // if there's a book attribtues file, include its contents
            if (attributesFile != null && attributesFile.exists()) {
                try (BufferedReader r =
                        new BufferedReader(new FileReader(attributesFile))) {
                    String line;
                    while ((line = r.readLine()) != null)
                        tout.println(line);
                }
            }

            tout.println();

            /*
             * Follow the "next" links from file to file, starting
             * with the startPage file.
             */
            next = startPage;
            do {
                String file = next;
                if (!exclude.contains(file))
                    tout.printf("include::%s[]%n%n", file);
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
             * Copy any files we haven't processed because they might
             * be include files or attribute configuration files.
             */
            for (String name : sourceDirectory.list()) {
                if (name.equals("toc.adoc") || name.equals(book))
                    continue;
                if (!seen.contains(name))
                    copy(name);
            }

            tout.close();
        } catch (IOException ex) {
            log.error(ex);
        }
    }

    /**
     * Process the named file in the source directory.
     *
     * If the file is in the exclude list, don't include
     * it in the book, but do read it to find the "next" link.
     *
     * Typically, each individual file contains a (redundant) top level
     * title/header line, e.g.,
     *
     * ...
     * ~~~~~
     * = Section Header
     *
     * [[tag]]
     *
     * Section Header
     * --------------
     *
     * Remove the top level header from the book file.
     */
    private void walk(String file) throws IOException {
        title = next = prev = null;
        File in = new File(sourceDirectory, file);
        try (BufferedReader r = new BufferedReader(new FileReader(in))) {
            String line;
            // read and extract information from the header
            while ((line = r.readLine()) != null) {
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

            if (exclude.contains(file))
                return;

	    // the rest of the file, after the header is copied
	    // to the book directory
	    File out = new File(bookDirectory, file);
	    try (PrintWriter w = new PrintWriter(out)) {
                boolean first = true;
		while ((line = r.readLine()) != null) {
                    if (first) {
                        // ignore empty lines
                        if (line.length() == 0)
                            continue;
                        // copy over include lines
                        if (line.startsWith("include::") &&
                                line.endsWith("[]")) {
                            w.println(line);
                            continue;
                        }
                        first = false;
                        // throw away the page title
                        if (line.startsWith("= "))
                            continue;
                        else {
                            String nline = r.readLine();
                            if (nline != null && nline.startsWith("=") &&
                                    nline.length() == line.length())
                                continue;
                            w.println(line);
                            line = nline;
                        }
                    }
		    w.println(line);
                }
	    }
        } catch (FileNotFoundException fex) {
            log.warn(in.toString() + ": can not open");
        }
    }

    /**
     * Copy the file from the source directory to the book directory.
     */
    private void copy(String file) throws IOException {
        File in = new File(sourceDirectory, file);
        File out = new File(bookDirectory, file);
        if (in.isDirectory())
            return;     // skip directories
        try (BufferedInputStream bin =
                new BufferedInputStream(new FileInputStream(in));
            BufferedOutputStream bout =
                new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[16*1024];
            int n;
            while ((n = bin.read(buf)) > 0)
                bout.write(buf, 0, n);
        }
    }
}

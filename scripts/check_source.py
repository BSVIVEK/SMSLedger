#!/usr/bin/env python3
"""Offline structural checks + tests of regex literals extracted from the Kotlin source.
This is NOT a Kotlin compiler or a replacement for Gradle/JUnit/device tests.
Requires Python 3 and a JDK 17 java command. Writes only into a temporary directory.
"""
from pathlib import Path
import json, re, subprocess, tempfile, xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
xmls = list((root/'app/src/main').rglob('*.xml'))
for file in xmls: ET.parse(file)
print(f'PASS: {len(xmls)} Android XML files parse')
ns = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
assert {e.attrib[ns+'name'] for e in manifest.findall('uses-permission')} == {'android.permission.READ_SMS'}
assert manifest.find('application').attrib[ns+'allowBackup'] == 'false'
assert not manifest.findall('.//service')
print('PASS: application manifest has READ_SMS only, no services and backup disabled')
patterns=[]
for file in (root/'core/src/main').rglob('*.kt'):
    for m in re.finditer(r'Regex\(("(?:[^"\\]|\\.)*")',file.read_text()):
        literal=m.group(1)
        if '$' in literal: # A literal end-anchor is fine; Kotlin interpolation is not.
            if '${' in literal or re.search(r'\$[A-Za-z_]',literal): continue
        patterns.append(json.loads(literal))
parser=(root/'core/src/main/kotlin/local/smsledger/core/Parser.kt').read_text()
def pattern(name):
    return json.loads(re.search(r'val '+name+r' = Regex\(("(?:[^"\\]|\\.)*")',parser).group(1))
def j(s): return json.dumps(s,ensure_ascii=True)
java="""import java.util.regex.*;
class RegexChecks {
 static int count=0;
 static void check(boolean value, String label) { if(!value) throw new AssertionError(label); count++; }
 public static void main(String[] args) {
"""
for p in patterns: java+='Pattern.compile('+j(p)+');\n'
java+='Pattern money=Pattern.compile('+j(pattern('money'))+');\n'
java+='Pattern account=Pattern.compile('+j(pattern('account'))+');\n'
java+='Pattern ignore=Pattern.compile('+j(pattern('ignore'))+',Pattern.CASE_INSENSITIVE);\n'
java+='Pattern merchant=Pattern.compile('+j(pattern('pattern'))+');\n'
java+='Pattern sender=Pattern.compile('+j(pattern('sender'))+',Pattern.CASE_INSENSITIVE);\n'
java+=r"""
var m=money.matcher("INR 1,23,456.78 spent on HDFC credit card XX1234");
check(m.find(),"Indian amount match");
check(m.group(2).equals("1,23,456.78"),"Indian grouping preserved");
check(new java.math.BigDecimal(m.group(2).replace(",","")).movePointRight(2).longValueExact()==12345678L,"exact minor units");
var a=account.matcher("HDFC credit card XX1234"); check(a.find(),"masked card match"); check(a.group(1).equals("XX1234"),"masked suffix");
var b=account.matcher("account 123456789012"); check(b.find(),"full account parsed transiently"); check(b.group(1).endsWith("9012"),"last four available");
check(ignore.matcher("OTP 987654 for INR 100 transaction").find(),"OTP excluded");
check(ignore.matcher("Your one time code is 123456").find(),"one-time code excluded");
check(ignore.matcher("UPI payment request received").find(),"collect request excluded");
check(!ignore.matcher("INR 100 spent at AMAZON on credit card").find(),"purchase accepted by ignore gate");
check(sender.matcher("AD-HDFCBK").matches(),"Indian sender");
check(sender.matcher("AD-HDFCBK-S").matches(),"sender suffix");
check(!sender.matcher("+919876543210").matches(),"personal number rejected");
var merchantMatcher=merchant.matcher("INR 100 debited from HDFC account XX1234 to AMAZON via UPI on 17-09-2026");
boolean foundAmazon=false;
while(merchantMatcher.find()) if(merchantMatcher.group(1).equals("AMAZON")) foundAmazon=true;
check(foundAmazon,"merchant clauses do not swallow recipient");
System.out.println("PASS: " + count + " Java regex/amount assertions");
}}
"""
with tempfile.TemporaryDirectory(prefix='smsledger-check-') as d:
    d=Path(d)
    source=d/'RegexChecks.java'; source.write_text(java)
    subprocess.run(['java',str(source)],check=True)
    subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(d),str(root/'scripts/GradleBootstrap.java')],check=True)
print(f'PASS: {len(patterns)} static Kotlin regex literals compile with the JVM regex engine')
print('PASS: GradleBootstrap.java compiles with JDK 17')
subprocess.run(['sh','-n',str(root/'gradlew')],check=True)
print('PASS: gradlew shell syntax')
unit=sum(len(re.findall(r'@Test\b',p.read_text())) for p in (root/'core/src/test').rglob('*.kt'))
instrumentation=sum(len(re.findall(r'@Test\b',p.read_text())) for p in (root/'app/src/androidTest').rglob('*.kt'))
print(f'INVENTORY (not executed): {unit} Kotlin unit tests, {instrumentation} Android instrumentation tests')

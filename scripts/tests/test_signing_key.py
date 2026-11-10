"""Synthetic interactive generation; no production key or GitHub credentials."""
import errno
import os
from pathlib import Path
import pty
import select
import shutil
import signal
import subprocess
import tempfile
import time
import unittest

SCRIPT = Path(__file__).resolve().parents[1]/'create-signing-key.sh'
PASSWORD = 'Synthetic-test-only-93!'


def terminal_run(answers, timeout=90):
    pid, terminal = pty.fork()
    if pid == 0:
        # Even bash -x must not expose the hidden answers.
        os.execv('/bin/bash', ['bash', '-x', str(SCRIPT)])
    output, index, deadline = b'', 0, time.monotonic() + timeout
    try:
        while time.monotonic() < deadline:
            if select.select([terminal], [], [], .1)[0]:
                try: data = os.read(terminal, 65536)
                except OSError as failure:
                    if failure.errno == errno.EIO: break
                    raise
                if not data: break
                output += data
                if index < len(answers) and answers[index][0].encode() in output:
                    os.write(terminal, answers[index][1].encode() + b'\n')
                    index += 1
            done, status = os.waitpid(pid, os.WNOHANG)
            if done:
                return os.waitstatus_to_exitcode(status), output.decode(errors='replace')
        else:
            os.kill(pid, signal.SIGKILL)
            os.waitpid(pid, 0)
            raise AssertionError('Interactive key generation timed out')
        _, status = os.waitpid(pid, 0)
        return os.waitstatus_to_exitcode(status), output.decode(errors='replace')
    finally:
        os.close(terminal)


@unittest.skipUnless(shutil.which('keytool') and shutil.which('shasum'), 'Requires JDK keytool and shasum')
class SigningKeyTest(unittest.TestCase):
    def answers(self, destination, password=PASSWORD, confirmation=PASSWORD):
        return [('Type CREATE:', 'CREATE'), ('nickname [IlyasYOY]:', ''),
                ('organization (optional;', 'Synthetic Test'), ('Country code', 'US'),
                ('NEW private directory', str(destination)), ('New password (', password),
                ('Repeat password:', confirmation)]

    def test_real_synthetic_key_permissions_subject_and_no_password_output(self):
        with tempfile.TemporaryDirectory(prefix='key-script-test-') as root:
            destination = Path(root)/'new key'
            status, output = terminal_run(self.answers(destination))
            self.assertEqual(0, status, output)
            self.assertNotIn(PASSWORD, output)
            self.assertEqual(0o700, destination.stat().st_mode & 0o777)
            self.assertEqual(0o600, (destination/'release.p12').stat().st_mode & 0o777)
            self.assertEqual({'release.p12', 'certificate.der', 'public-info.txt'}, {p.name for p in destination.iterdir()})
            info = (destination/'public-info.txt').read_text()
            self.assertIn('Developer: IlyasYOY', info)
            self.assertNotIn(PASSWORD, info)
            result = subprocess.run(['keytool', '-printcert', '-file', str(destination/'certificate.der')], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode)
            self.assertIn('CN=IlyasYOY', result.stdout)
            self.assertIn('4096-bit RSA', result.stdout)
            before = (destination/'release.p12').read_bytes()
            status, output = terminal_run(self.answers(destination))
            self.assertNotEqual(0, status)
            self.assertEqual(before, (destination/'release.p12').read_bytes())

    def test_mismatched_password_does_not_create_directory_or_echo_password(self):
        with tempfile.TemporaryDirectory() as root:
            destination = Path(root)/'not-created'
            status, output = terminal_run(self.answers(destination, confirmation='Other-synthetic-password'))
            self.assertNotEqual(0, status)
            self.assertFalse(destination.exists())
            self.assertNotIn(PASSWORD, output)
            self.assertNotIn('Other-synthetic-password', output)

    def test_redirected_noninteractive_execution_is_refused(self):
        result = subprocess.run(['bash', str(SCRIPT)], input='CREATE\n', capture_output=True, text=True, timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Run interactively', result.stderr)


if __name__ == '__main__': unittest.main()
